package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what an attempt produced: where its canonical file is stored, and the job's outcome from
 * inside the transaction that publishes its data (LLD section 8.1).
 */
@Service
public class JobCompletion {

  private final JobRunRepository runs;
  private final Clock clock;

  /** Creates the service. */
  public JobCompletion(JobRunRepository runs, Clock clock) {
    this.runs = runs;
    this.clock = clock;
  }

  /**
   * Records where the attempt's canonical file is stored, in its own transaction: the file stays
   * the attempt's evidence even if publication later fails (LLD sections 8.1 and 17.5).
   */
  @Transactional
  public void recordCanonicalFile(ClaimedJob job, String objectKey) {
    runs.recordCanonicalFile(job.runId(), objectKey);
  }

  /**
   * Records the job's final status, counts, and error count, and the attempt's success.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException if called outside a
   *     transaction: the outcome must commit with the published data
   * @throws IllegalStateException if the job is not running this attempt
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(ClaimedJob job, JobOutcome outcome) {
    runs.completeRun(job.job().id(), job.runId(), job.attemptNumber(), outcome, Instant.now(clock));
  }
}
