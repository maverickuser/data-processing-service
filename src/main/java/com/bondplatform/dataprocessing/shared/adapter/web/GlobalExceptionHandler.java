package com.bondplatform.dataprocessing.shared.adapter.web;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that escapes a controller into a problem-details response.
 *
 * <p>Errors the web framework detects itself (unknown route, unsupported method or media type,
 * missing or malformed input) keep their own status and are given this service's problem shape. A
 * controller asks for a documented problem by throwing {@link ApiProblemException}. A database that
 * cannot be reached in time is {@code 503}. Anything else is an unexpected failure: it becomes
 * {@code 500} and is logged here, once.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String UNEXPECTED_FAILURE_DETAIL = "The request could not be completed.";
  private static final String STORAGE_UNAVAILABLE_DETAIL =
      "The service is temporarily unavailable. Retry the identical request after Retry-After.";
  private static final String RETRY_AFTER_SECONDS = "5";
  private static final String UNKNOWN_ROUTE_DETAIL = "No resource exists at this path.";
  private static final String FRAMEWORK_PACKAGE_PREFIX = "org.springframework.";

  private final ProblemDetailFactory problems;

  /** Creates the handler. */
  public GlobalExceptionHandler(ProblemDetailFactory problems) {
    this.problems = problems;
  }

  /**
   * Responds {@code 500} to a failure nothing else handles. The cause is logged with the
   * correlation identifier and is never shown to the caller.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpectedFailure(Exception exception) {
    ProblemDetail problem = problems.create(ProblemType.INTERNAL_ERROR, UNEXPECTED_FAILURE_DETAIL);
    LOG.error(
        "Unexpected failure, correlationId={}",
        ProblemDetailFactory.correlationIdOf(problem),
        exception);
    return problem;
  }

  /** Answers with the documented problem a controller asked for. */
  @ExceptionHandler(ApiProblemException.class)
  public ResponseEntity<ProblemDetail> requestedProblem(ApiProblemException exception) {
    ProblemDetail problem = problems.create(exception.type(), exception.detail());
    if (!exception.errors().isEmpty()) {
      problem.setProperty("errors", exception.errors());
    }
    return ResponseEntity.status(exception.type().status()).body(problem);
  }

  /**
   * Responds {@code 503} with {@code Retry-After} when the database is unreachable, too slow, or
   * holding a lock for too long. The caller may retry the identical request.
   */
  @ExceptionHandler({
    TransientDataAccessException.class,
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class
  })
  public ResponseEntity<ProblemDetail> storageUnavailable(Exception exception) {
    ProblemDetail problem =
        problems.create(ProblemType.SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE_DETAIL);
    LOG.warn(
        "Storage unavailable, correlationId={}",
        ProblemDetailFactory.correlationIdOf(problem),
        exception);
    return ResponseEntity.status(ProblemType.SERVICE_UNAVAILABLE.status())
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(problem);
  }

  /**
   * Gives a framework-detected error this service's problem shape while keeping its status and
   * headers, for example {@code Allow} on {@code 405}.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemType type = ProblemType.forFrameworkStatus(status.value());
    if (type == ProblemType.INTERNAL_ERROR) {
      return ResponseEntity.status(type.status())
          .headers(headers)
          .body(unexpectedFailure(exception));
    }
    ProblemDetail problem = problems.create(type, detailOf(exception, type));
    return ResponseEntity.status(type.status()).headers(headers).body(problem);
  }

  /**
   * Returns the framework's own caller-safe explanation, or the type's title when it has none.
   *
   * <p>Only exceptions defined by the web framework are trusted to carry caller-safe text. An
   * {@link ErrorResponse} defined anywhere else gets the title, so internal text cannot leak.
   */
  private static String detailOf(Exception exception, ProblemType type) {
    if (type == ProblemType.NOT_FOUND) {
      return UNKNOWN_ROUTE_DETAIL;
    }
    boolean definedByFramework =
        exception.getClass().getName().startsWith(FRAMEWORK_PACKAGE_PREFIX);
    if (definedByFramework && exception instanceof ErrorResponse errorResponse) {
      String detail = errorResponse.getBody().getDetail();
      if (detail != null && !detail.isBlank()) {
        return detail;
      }
    }
    return type.title() + ".";
  }
}
