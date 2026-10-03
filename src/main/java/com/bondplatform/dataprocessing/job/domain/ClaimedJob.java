package com.bondplatform.dataprocessing.job.domain;

import java.util.UUID;

/**
 * A job a worker has started an attempt of.
 *
 * @param job the job as it was when the attempt started
 * @param runId the identity of this attempt's processing run
 * @param attemptNumber this attempt's number, from 1
 */
public record ClaimedJob(StoredJob job, UUID runId, int attemptNumber) {

  /** Rejects an attempt number below 1. */
  public ClaimedJob {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("Attempt numbers start at 1");
    }
  }
}
