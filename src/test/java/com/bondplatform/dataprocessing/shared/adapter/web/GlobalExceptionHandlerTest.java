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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

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

  private static ProblemDetail problemOf(ResponseEntity<Object> response) {
    return (ProblemDetail) Objects.requireNonNull(response.getBody());
  }
}
