package com.bondplatform.dataprocessing.job.application;

/**
 * An attempt failed for a reason that may pass, such as S3 or the database being briefly
 * unavailable (LLD section 8.3). The job is tried again.
 *
 * <p>The code and detail are stored with the attempt; neither may contain credentials, presigned
 * URLs, or source data.
 */
public class TemporaryFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  /** Creates the exception with a stable code and a short explanation. */
  public TemporaryFailureException(String code, String detail, Throwable cause) {
    super(detail, cause);
    this.code = code;
  }

  /** Returns the stable code stored with the attempt. */
  public String code() {
    return code;
  }
}
