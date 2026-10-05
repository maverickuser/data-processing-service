package com.bondplatform.dataprocessing.operations.application;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Fails jobs that would otherwise wait forever (LLD section 23.3).
 *
 * <p>A job is stuck when it is {@code QUEUED}, {@code PROCESSING} or {@code RETRY_PENDING} and
 * either has made no progress for an hour (decided 2026-10-05), or is processing its final attempt
 * and that attempt began longer ago than an attempt can run. The first rule catches a job whose
 * message reached the dead-letter queue before an attempt was recorded; the second fails a job
 * whose last invocation died, without waiting the hour. The second rule waits the queue's 16-minute
 * visibility timeout rather than the 15-minute Lambda timeout, so a run that is still alive is
 * never failed.
 *
 * <p>A stuck job becomes {@code FAILED}; a run still recorded as running ends as a temporary
 * failure with {@code ATTEMPT_ABANDONED}, which the job's error list shows. A job failed before any
 * attempt has no run, so it reports no error. Each job is failed in its own transaction, and a job
 * a worker holds at that moment is left for the next sweep.
 */
public final class StuckJobFailer {

  /** How long an unfinished job may go without progress. */
  static final Duration PROGRESS_LIMIT = Duration.ofHours(1);

  /** How long after its start a final attempt is known to be dead: the visibility timeout. */
  static final Duration ATTEMPT_LIMIT = Duration.ofMinutes(16);

  /** The code a run ended here carries, as when a worker finds a run abandoned. */
  static final String ATTEMPT_ABANDONED = "ATTEMPT_ABANDONED";

  /** The most jobs one sweep fails. */
  static final int BATCH_SIZE = 100;

  private static final Logger LOG = LoggerFactory.getLogger(StuckJobFailer.class);

  private final StuckJobStore store;
  private final TransactionOperations transactions;
  private final int finalAttempt;
  private final Clock clock;

  /** Creates a failer for jobs allowed {@code finalAttempt} attempts. */
  public StuckJobFailer(
      StuckJobStore store, TransactionOperations transactions, int finalAttempt, Clock clock) {
    this.store = store;
    this.transactions = transactions;
    this.finalAttempt = finalAttempt;
    this.clock = clock;
  }

  /** Fails up to {@link #BATCH_SIZE} stuck jobs and returns how many it failed. */
  public int failStuckJobs() {
    Instant now = clock.instant();
    StuckJobRule rule =
        new StuckJobRule(now.minus(PROGRESS_LIMIT), now.minus(ATTEMPT_LIMIT), finalAttempt);
    List<JobId> stuck = store.findStuck(rule, BATCH_SIZE);
    int failed = 0;
    for (JobId job : stuck) {
      Boolean done =
          transactions.execute(status -> store.failIfStuck(job, rule, ATTEMPT_ABANDONED, now));
      if (Boolean.TRUE.equals(done)) {
        failed++;
        LOG.warn("Stuck job failed, jobId={}", job);
      }
    }
    return failed;
  }
}
