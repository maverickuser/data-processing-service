package com.bondplatform.dataprocessing.review.application;

/** A read request's parameter cannot be used, such as a changed page token; a {@code 400}. */
public class InvalidQueryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String detail;

  /** Creates the exception with a detail safe to show the caller. */
  public InvalidQueryException(String detail) {
    super(detail);
    this.detail = detail;
  }

  /** Returns the detail safe to show the caller. */
  public String detail() {
    return detail;
  }
}
