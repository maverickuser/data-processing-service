package com.bondplatform.dataprocessing.job.domain;

/** How one processing attempt ended, or that it is still running. Stored in processing_runs. */
public enum RunStatus {
  /** The attempt is running, or its invocation died without recording an end. */
  RUNNING,
  /** The attempt finished the job. */
  SUCCEEDED,
  /** The attempt failed for a reason that may pass; the job may run again. */
  FAILED_TEMPORARY,
  /** The attempt failed for a reason a retry cannot fix; the job is failed. */
  FAILED_PERMANENT
}
