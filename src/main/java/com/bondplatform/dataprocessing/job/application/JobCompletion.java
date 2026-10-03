package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Finishes a job from inside the transaction that publishes its data (LLD section 8.1). */
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
   * Records the job's final status, counts, and error count, and the attempt's success.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException if called outside a
   *     transaction: the outcome must commit with the published data
   * @throws IllegalStateException if the job is not running this attempt
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(ClaimedJob job, JobOutcome outcome) {
    runs.completeRun(job.job().id(), job.runId(), outcome, Instant.now(clock));
  }
}
