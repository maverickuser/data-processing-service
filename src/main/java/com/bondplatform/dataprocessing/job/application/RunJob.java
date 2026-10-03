package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs one attempt of a job, for one queue message (LLD sections 8 and 23.3).
 *
 * <p>The attempt starts in its own short transaction: the job is locked, any run left running by an
 * invocation that died is ended, and a new run is recorded. The dataset handler then does the work
 * and finishes the job in its publication transaction. A message for a job that has already
 * finished does nothing, so a redelivery never publishes twice.
 */
@Service
public class RunJob {

  /** Code of a run whose invocation died without recording an end. */
  static final String ATTEMPT_ABANDONED = "ATTEMPT_ABANDONED";

  /** Code of a handler that returned without finishing the job: a defect, not a data problem. */
  static final String JOB_NOT_FINISHED = "JOB_NOT_FINISHED";

  /** Code of a job whose dataset no handler in this deployment processes. */
  static final String NO_DATASET_HANDLER = "NO_DATASET_HANDLER";

  /** Code of a failure no handler anticipated; treated as temporary. */
  static final String UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE";

  private static final Logger LOG = LoggerFactory.getLogger(RunJob.class);

  private final JobRunRepository runs;
  private final Map<DatasetUrn, DatasetHandler> handlers;
  private final TransactionOperations transactions;
  private final RetryPolicy retryPolicy;
  private final Clock clock;
  private final IdSupplier idSupplier;

  /**
   * Creates the use case.
   *
   * @throws IllegalStateException if two handlers process the same dataset
   */
  public RunJob(
      JobRunRepository runs,
      List<DatasetHandler> handlers,
      TransactionOperations transactions,
      RetryPolicy retryPolicy,
      Clock clock,
      IdSupplier idSupplier) {
    this.runs = runs;
    this.handlers =
        handlers.stream().collect(Collectors.toMap(DatasetHandler::dataset, Function.identity()));
    this.transactions = transactions;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
    this.idSupplier = idSupplier;
  }

  /**
   * Runs one attempt of the job.
   *
   * @return what the queue message's fate should be
   */
  public RunResult run(JobId jobId) {
    MDC.put("jobId", jobId.toString());
    try {
      Claim claim = Objects.requireNonNull(transactions.execute(status -> claim(jobId)));
      return switch (claim) {
        case Claim.UnknownJob unknown -> {
          LOG.warn("No job has this ID; the message is dropped");
          yield RunResult.UNKNOWN_JOB;
        }
        case Claim.AlreadyFinished finished -> {
          LOG.info("Job already finished as {}; nothing to do", finished.status());
          yield RunResult.ALREADY_FINISHED;
        }
        case Claim.AttemptsUsedUp usedUp -> {
          LOG.warn("Every attempt was used; the job is failed");
          yield RunResult.FINISHED;
        }
        case Claim.Started started -> attempt(started.job());
      };
    } finally {
      MDC.remove("attemptNumber");
      MDC.remove("jobId");
    }
  }

  private RunResult attempt(ClaimedJob job) {
    MDC.put("attemptNumber", String.valueOf(job.attemptNumber()));
    DatasetHandler handler = handlers.get(job.job().dataset());
    if (handler == null) {
      // A deployment defect, but failing the job once beats a message that loops forever.
      fail(
          job,
          RunStatus.FAILED_PERMANENT,
          NO_DATASET_HANDLER,
          "No handler processes dataset " + job.job().dataset().value());
      return RunResult.FINISHED;
    }
    try {
      handler.process(job);
    } catch (PermanentFailureException e) {
      fail(job, RunStatus.FAILED_PERMANENT, e.code(), String.valueOf(e.getMessage()));
      return RunResult.FINISHED;
    } catch (TemporaryFailureException e) {
      return failTemporarily(job, e.code(), String.valueOf(e.getMessage()));
    } catch (RuntimeException e) {
      // The worker's outermost boundary for the job: an unanticipated failure is logged once and
      // treated as temporary. Its message may quote source data, so only its type and where it
      // was thrown are logged and stored.
      LOG.error(
          "Attempt failed unexpectedly, type={}, at={}",
          e.getClass().getName(),
          e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
      return failTemporarily(job, UNEXPECTED_FAILURE, e.getClass().getName());
    }
    if (!isFinished(job.job().id())) {
      fail(
          job,
          RunStatus.FAILED_PERMANENT,
          JOB_NOT_FINISHED,
          "The dataset handler returned without finishing the job");
    }
    return RunResult.FINISHED;
  }

  private Claim claim(JobId jobId) {
    Optional<StoredJob> stored = runs.lockForRun(jobId);
    if (stored.isEmpty()) {
      return new Claim.UnknownJob();
    }
    StoredJob job = stored.get();
    if (job.status().isTerminal()) {
      return new Claim.AlreadyFinished(job.status());
    }
    Instant now = Instant.now(clock);
    runs.endAbandonedRuns(jobId, ATTEMPT_ABANDONED, now);
    if (job.attemptCount() >= retryPolicy.maxAttempts()) {
      // Every attempt was used, the last one by an invocation that died without recording an end.
      runs.failJob(jobId, job.attemptCount(), now);
      return new Claim.AttemptsUsedUp();
    }
    ClaimedJob claimed = new ClaimedJob(job, idSupplier.nextId(), job.attemptCount() + 1);
    runs.startRun(jobId, claimed.runId(), claimed.attemptNumber(), now);
    return new Claim.Started(claimed);
  }

  private boolean isFinished(JobId jobId) {
    Boolean finished =
        transactions.execute(
            status -> runs.lockForRun(jobId).map(job -> job.status().isTerminal()).orElse(false));
    return Boolean.TRUE.equals(finished);
  }

  /**
   * Records a temporary failure: the job runs again after the policy's delay, or, after its last
   * attempt, is failed.
   */
  private RunResult failTemporarily(ClaimedJob job, String code, String detail) {
    Optional<Duration> delay = retryPolicy.delayAfterFailedAttempt(job.attemptNumber());
    if (delay.isEmpty()) {
      fail(job, RunStatus.FAILED_TEMPORARY, code, detail, JobStatus.FAILED);
      return RunResult.ATTEMPTS_EXHAUSTED;
    }
    fail(job, RunStatus.FAILED_TEMPORARY, code, detail, JobStatus.RETRY_PENDING);
    return RunResult.retryAfter(delay.get());
  }

  private void fail(ClaimedJob job, RunStatus runStatus, String code, String detail) {
    fail(job, runStatus, code, detail, JobStatus.FAILED);
  }

  private void fail(
      ClaimedJob job, RunStatus runStatus, String code, String detail, JobStatus jobStatus) {
    transactions.executeWithoutResult(
        status ->
            runs.failRun(
                job.job().id(),
                job.runId(),
                job.attemptNumber(),
                runStatus,
                code,
                detail,
                jobStatus,
                Instant.now(clock)));
    LOG.warn("Attempt failed, code={}, jobStatus={}", code, jobStatus);
  }

  /** What claiming a job found. */
  private sealed interface Claim {

    /** No job has this ID. */
    record UnknownJob() implements Claim {}

    /** Every attempt had been used; the job is now failed. */
    record AttemptsUsedUp() implements Claim {}

    /** The job has already finished; the message is a redelivery. */
    record AlreadyFinished(JobStatus status) implements Claim {}

    /** An attempt has started. */
    record Started(ClaimedJob job) implements Claim {}
  }
}
