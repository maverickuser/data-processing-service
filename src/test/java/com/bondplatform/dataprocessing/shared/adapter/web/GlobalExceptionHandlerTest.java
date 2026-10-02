package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private static final UUID CORRELATION_ID =
      UUID.fromString("3df630b2-95d3-4f8f-8288-f7b0a1081b61");

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(new ProblemDetailFactory(() -> CORRELATION_ID));
  private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest());
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();
  private final Logger handlerLogger =
      (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @BeforeEach
  void captureLog() {
    log.start();
    handlerLogger.addAppender(log);
  }

  @AfterEach
  void releaseLog() {
    handlerLogger.detachAppender(log);
  }

  @Test
  void requestedProblemKeepsItsTypeDetailAndErrors() {
    ProblemError error = new ProblemError("body", "/subject", null, "REQUIRED", "Is required.");

    ResponseEntity<ProblemDetail> response =
        handler.requestedProblem(
            new ApiProblemException(ProblemType.INVALID_REQUEST, "Not valid.", List.of(error)));

    ProblemDetail problem = Objects.requireNonNull(response.getBody());
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("Not valid.");
    assertThat(problem.getProperties())
        .containsEntry("code", "INVALID_REQUEST")
        .containsEntry("errors", List.of(error));
  }

  @Test
  void requestedProblemWithoutErrorsHasNoErrorsProperty() {
    ResponseEntity<ProblemDetail> response =
        handler.requestedProblem(
            new ApiProblemException(ProblemType.IDEMPOTENCY_CONFLICT, "Already accepted."));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(Objects.requireNonNull(response.getBody()).getProperties())
        .containsEntry("code", "IDEMPOTENCY_CONFLICT")
        .doesNotContainKey("errors");
  }

  @Test
  void unreachableOrSlowStorageIsServiceUnavailableWithRetryAfter() {
    ResponseEntity<ProblemDetail> response =
        handler.storageUnavailable(
            new CannotAcquireLockException("group stayed locked: password=secret"));

    ProblemDetail problem = Objects.requireNonNull(response.getBody());
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    assertThat(problem.getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
    assertThat(problem.getDetail()).doesNotContain("secret");
    assertThat(log.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
  }

  @Test
  void transactionThatCannotStartIsServiceUnavailable() {
    ResponseEntity<ProblemDetail> response =
        handler.storageUnavailable(new CannotCreateTransactionException("no connection"));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
  }

  @Test
  void unexpectedFailureIsInternalErrorWithoutLeakingTheCause() {
    ProblemDetail problem = handler.unexpectedFailure(new IllegalStateException("password=secret"));

    assertThat(problem.getStatus()).isEqualTo(500);
    assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(problem.getDetail()).doesNotContain("secret");
  }

  @Test
  void unexpectedFailureIsLoggedOnceWithItsCorrelationId() {
    IllegalStateException cause = new IllegalStateException("database unreachable");

    handler.unexpectedFailure(cause);

    assertThat(log.list).hasSize(1);
    ILoggingEvent event = log.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
    assertThat(event.getFormattedMessage()).contains(CORRELATION_ID.toString());
    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("database unreachable");
  }

  @Test
  void frameworkErrorKeepsItsStatusHeadersAndExplanation() {
    HttpRequestMethodNotSupportedException exception =
        new HttpRequestMethodNotSupportedException("POST", List.of("GET"));

    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            exception, null, exception.getHeaders(), HttpStatus.METHOD_NOT_ALLOWED, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
    ProblemDetail problem = problemOf(response);
    assertThat(problem.getProperties())
        .containsEntry("code", "METHOD_NOT_ALLOWED")
        .containsEntry("correlationId", CORRELATION_ID.toString());
    assertThat(problem.getDetail()).contains("POST");
    assertThat(log.list).isEmpty();
  }

  @Test
  void unknownRouteHasFixedExplanation() {
    NoResourceFoundException exception =
        new NoResourceFoundException(HttpMethod.GET, "/v1/unknown", "v1/unknown");

    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            exception, null, HttpHeaders.EMPTY, HttpStatus.NOT_FOUND, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(problemOf(response).getDetail()).isEqualTo("No resource exists at this path.");
  }

  @Test
  void frameworkErrorWithoutAnExplanationUsesTheTitle() {
    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalArgumentException("internal detail"),
            null,
            HttpHeaders.EMPTY,
            HttpStatus.BAD_REQUEST,
            request);

    ProblemDetail problem = problemOf(response);
    assertThat(problem.getDetail()).isEqualTo("Invalid Request.");
  }

  @Test
  void errorResponseDefinedOutsideTheFrameworkDoesNotSupplyTheDetail() {
    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new ApplicationDefinedErrorResponse(),
            null,
            HttpHeaders.EMPTY,
            HttpStatus.BAD_REQUEST,
            request);

    assertThat(problemOf(response).getDetail()).isEqualTo("Invalid Request.");
  }

  @Test
  void frameworkServerErrorIsTreatedAsUnexpectedFailure() {
    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalStateException("conversion failed"),
            null,
            HttpHeaders.EMPTY,
            HttpStatus.INTERNAL_SERVER_ERROR,
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    ProblemDetail problem = problemOf(response);
    assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(problem.getDetail()).doesNotContain("conversion failed");
    assertThat(log.list).hasSize(1);
  }

  /** An exception that mimics the framework's contract but is not defined by it. */
  private static final class ApplicationDefinedErrorResponse extends RuntimeException
      implements ErrorResponse {

    @Override
    public HttpStatusCode getStatusCode() {
      return HttpStatus.BAD_REQUEST;
    }

    @Override
    public ProblemDetail getBody() {
      return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "jdbc password=secret");
    }
  }

  private static ProblemDetail problemOf(ResponseEntity<Object> response) {
    return (ProblemDetail) Objects.requireNonNull(response.getBody());
  }
}
