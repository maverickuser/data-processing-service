package com.bondplatform.dataprocessing.job.domain;

/** Where a job is in its life (LLD section 9). The names are part of the public API. */
public enum JobStatus {
  /** Accepted and waiting for a worker. */
  QUEUED,
  /** A worker is running an attempt. */
  PROCESSING,
  /** An attempt failed temporarily and another will follow. */
  RETRY_PENDING,
  /** Finished with every record accepted. */
  COMPLETED,
  /** Finished with some data accepted and some rejected. */
  COMPLETED_WITH_ERRORS,
  /** Finished without publishing anything. */
  FAILED;

  /** Returns whether the job will never run again. */
  public boolean isTerminal() {
    return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED;
  }
}
