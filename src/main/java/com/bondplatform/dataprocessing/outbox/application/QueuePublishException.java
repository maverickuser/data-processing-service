package com.bondplatform.dataprocessing.outbox.application;

/**
 * A queue did not accept an event. The event stays pending and is tried again later.
 *
 * <p>The message is stored with the event as its last error, so it must not contain credentials,
 * URLs with signatures, or the payload.
 */
public final class QueuePublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public QueuePublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
