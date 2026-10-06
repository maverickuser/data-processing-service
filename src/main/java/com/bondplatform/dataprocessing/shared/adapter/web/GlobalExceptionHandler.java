package com.bondplatform.dataprocessing.shared.adapter.web;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
    RecoverableDataAccessException.class,
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
   * Returns a fixed explanation for a framework-detected error. The framework's own text can quote
   * the request, such as a parameter's value or a content type, so it is never used; only a
   * parameter's name, which this service declares, may appear.
   */
  private static String detailOf(Exception exception, ProblemType type) {
    if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
      return "The " + mismatch.getName() + " parameter is not valid.";
    }
    if (exception instanceof MissingServletRequestParameterException missing) {
      return "The " + missing.getParameterName() + " parameter is required.";
    }
    return switch (type) {
      case NOT_FOUND -> UNKNOWN_ROUTE_DETAIL;
      case METHOD_NOT_ALLOWED -> "This path does not accept this method; see the Allow header.";
      case NOT_ACCEPTABLE -> "This path responds only with the media type its API documents.";
      case UNSUPPORTED_MEDIA_TYPE -> "This path does not accept a body of this media type.";
      case REQUEST_TOO_LARGE -> "The request body is too large.";
      default -> type.title() + ".";
    };
  }
}
