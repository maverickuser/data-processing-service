package com.bondplatform.dataprocessing.admission.application;

/**
 * Thrown when an idempotency key or an event identity that was already accepted is presented again
 * with different content, or the two identities belong to different earlier submissions.
 */
public class IdempotencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public IdempotencyConflictException(String idempotencyKey) {
    super(
        "Idempotency key "
            + idempotencyKey
            + " or its event identity was already accepted with different content");
  }
}
