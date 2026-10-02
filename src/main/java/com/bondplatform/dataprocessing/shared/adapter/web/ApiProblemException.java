package com.bondplatform.dataprocessing.shared.adapter.web;

import java.util.List;

/**
 * Thrown by a controller to answer with a documented problem. {@link GlobalExceptionHandler} turns
 * it into the problem-details response, so every HTTP error is built in one place.
 */
public final class ApiProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProblemType type;
  private final transient List<ProblemError> errors;

  /**
   * Creates the exception.
   *
   * @param type the kind of problem
   * @param detail an explanation of this occurrence that is safe to show to the caller
   * @param errors the individual errors of the request, or none
   */
  public ApiProblemException(ProblemType type, String detail, List<ProblemError> errors) {
    super(detail);
    this.type = type;
    this.errors = List.copyOf(errors);
  }

  /** Creates the exception for a problem with no individual errors. */
  public ApiProblemException(ProblemType type, String detail) {
    this(type, detail, List.of());
  }

  /** Returns the kind of problem. */
  public ProblemType type() {
    return type;
  }

  /** Returns the explanation shown to the caller. */
  public String detail() {
    return String.valueOf(getMessage());
  }

  /** Returns the individual errors of the request; empty when there are none. */
  public List<ProblemError> errors() {
    return errors;
  }
}
