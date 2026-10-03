package com.bondplatform.dataprocessing.job.application;

/**
 * An attempt failed for a reason a retry cannot fix, such as a missing source object or a file that
 * breaks its contract (LLD section 8.3). The job is failed and not tried again.
 *
 * <p>The code is part of the public status API; the detail is stored with the attempt. Neither may
 * contain credentials, presigned URLs, or source data.
 */
public class PermanentFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  /** Creates the exception with a stable code and a short explanation. */
  public PermanentFailureException(String code, String detail) {
    super(detail);
    this.code = code;
  }

  /** Returns the stable code stored with the attempt. */
  public String code() {
    return code;
  }
}
