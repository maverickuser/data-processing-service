package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;

/**
 * Processes jobs of one dataset: reads the source, runs both stages, and publishes the result.
 *
 * <p>A handler finishes the job itself, by calling {@link JobCompletion#complete} inside the
 * transaction that publishes its data, so that the data and the job's final status commit together
 * (LLD section 8.1).
 */
public interface DatasetHandler {

  /** Returns the dataset this handler processes. */
  DatasetUrn dataset();

  /**
   * Processes one attempt of the job.
   *
   * @throws TemporaryFailureException if the attempt failed for a reason that may pass
   * @throws PermanentFailureException if a retry cannot fix the failure
   */
  void process(ClaimedJob job);
}
