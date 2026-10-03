package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores jobs' attempts: the job's status and attempt count in ingestion_requests, and one row per
 * attempt in processing_runs. Every method runs in the caller's transaction.
 */
public interface JobRunRepository {

  /**
   * Returns the job, locked until the transaction ends so that no other worker changes it
   * meanwhile, or empty if no job has this ID.
   */
  Optional<StoredJob> lockForRun(JobId id);

  /**
   * Ends every run of the job still recorded as running: their invocations died without recording
   * an end. They become temporary failures with the given code.
   */
  void endAbandonedRuns(JobId id, String code, Instant now);

  /**
   * Records the start of an attempt: the job becomes {@code PROCESSING} with this attempt count,
   * and a running processing run is added.
   */
  void startRun(JobId id, UUID runId, int attemptNumber, Instant now);

  /**
   * Fails a job whose attempts have all been used, without starting another. The job must be on the
   * given attempt and not finished; otherwise nothing changes.
   */
  void failJob(JobId id, int attemptCount, Instant now);

  /**
   * Records that the attempt finished the job with this outcome.
   *
   * @throws IllegalStateException if the job is not running this attempt: another attempt has taken
   *     over, or the job has ended
   */
  void completeRun(JobId id, UUID runId, int attemptNumber, JobOutcome outcome, Instant now);

  /**
   * Records that the attempt failed, and the job's resulting status: {@code FAILED}, which also
   * sets its completion time, or a status from which it runs again. An attempt that is no longer
   * the job's current one changes nothing.
   */
  void failRun(
      JobId id,
      UUID runId,
      int attemptNumber,
      RunStatus runStatus,
      String code,
      String detail,
      JobStatus jobStatus,
      Instant now);
}
