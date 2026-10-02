package com.bondplatform.dataprocessing.shared.adapter.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns exceptions that escape a controller into problem-details responses.
 *
 * <p>This is the only place that converts exceptions to HTTP errors, and the only place an
 * unexpected exception is logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ProblemDetailFactory problems;

  /** Creates the handler. */
  public GlobalExceptionHandler(ProblemDetailFactory problems) {
    this.problems = problems;
  }

  /** Responds {@code 404} when no route matches the request. */
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ProblemDetail handleUnknownRoute(Exception exception) {
    return problems.create(ProblemType.NOT_FOUND, "No resource exists at this path.");
  }

  /**
   * Responds {@code 500} for anything unexpected. The cause is logged with the correlation
   * identifier and never shown to the caller.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception exception) {
    ProblemDetail problem =
        problems.create(ProblemType.INTERNAL_ERROR, "The request could not be completed.");
    LOG.error("Unexpected failure, correlationId={}", correlationIdOf(problem), exception);
    return problem;
  }

  private static Object correlationIdOf(ProblemDetail problem) {
    var properties = problem.getProperties();
    return properties == null ? "unknown" : properties.getOrDefault("correlationId", "unknown");
  }
}
