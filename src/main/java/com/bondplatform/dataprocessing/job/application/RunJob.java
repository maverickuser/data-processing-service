package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.application.Metric;
import com.bondplatform.dataprocessing.shared.application.Metrics;
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
 *
 * <p>Each attempt that ends, and each job failed because its attempts were used, records the job's
 * status as a {@link Metric#JOB_OUTCOME}; an attempt also records its {@link Metric#RUN_DURATION}
 * (LLD section 23.8).
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

  /** Metric dimension: the job's dataset URN. */
  static final String DATASET = "Dataset";

  /** Metric dimension: the job's status once the attempt ended. */
  static final String OUTCOME = "Outcome";

  private static final Logger LOG = LoggerFactory.getLogger(RunJob.class);

  private final JobRunRepository runs;
  private final Map<DatasetUrn, DatasetHandler> handlers;
  private final TransactionOperations transactions;
  private final RetryPolicy retryPolicy;
  private final Clock clock;
  private final IdSupplier idSupplier;
  private final Metrics metrics;

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
      IdSupplier idSupplier,
      Metrics metrics) {
    this.runs = runs;
    this.handlers =
        handlers.stream().collect(Collectors.toMap(DatasetHandler::dataset, Function.identity()));
    this.transactions = transactions;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
    this.idSupplier = idSupplier;
    this.metrics = metrics;
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
          recordOutcome(usedUp.dataset(), JobStatus.FAILED);
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
    Instant started = Instant.now(clock);
    Attempt ended = runAttempt(job);
    DatasetUrn dataset = job.job().dataset();
    long millis = Duration.between(started, Instant.now(clock)).toMillis();
    LOG.info("Attempt ended, jobStatus={}, durationMillis={}", ended.jobStatus(), millis);
    recordOutcome(dataset, ended.jobStatus());
    record(Metric.RUN_DURATION, millis, Map.of(DATASET, dataset.value()));
    return ended.result();
  }

  private void recordOutcome(DatasetUrn dataset, JobStatus status) {
    record(Metric.JOB_OUTCOME, 1, Map.of(DATASET, dataset.value(), OUTCOME, status.name()));
  }

  /**
   * Records a metric after the job's state is committed; a failure to record is logged and does not
   * change how the message is handled.
   */
  private void record(Metric metric, long value, Map<String, String> dimensions) {
    try {
      metrics.record(metric, value, dimensions);
    } catch (RuntimeException e) {
      LOG.warn("Metric not recorded, metric={}, type={}", metric, e.getClass().getName());
    }
  }

  private Attempt runAttempt(ClaimedJob job) {
    DatasetHandler handler = handlers.get(job.job().dataset());
    if (handler == null) {
      // A deployment defect, but failing the job once beats a message that loops forever.
      fail(
          job,
          RunStatus.FAILED_PERMANENT,
          NO_DATASET_HANDLER,
          "No handler processes dataset " + job.job().dataset().value());
      return Attempt.FAILED;
    }
    try {
      handler.process(job);
    } catch (PermanentFailureException e) {
      fail(job, RunStatus.FAILED_PERMANENT, e.code(), String.valueOf(e.getMessage()));
      return Attempt.FAILED;
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
    Optional<JobStatus> finished = finishedStatus(job.job().id());
    if (finished.isEmpty()) {
      fail(
          job,
          RunStatus.FAILED_PERMANENT,
          JOB_NOT_FINISHED,
          "The dataset handler returned without finishing the job");
      return Attempt.FAILED;
    }
    return new Attempt(RunResult.FINISHED, finished.get());
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
      return new Claim.AttemptsUsedUp(job.dataset());
    }
    ClaimedJob claimed = new ClaimedJob(job, idSupplier.nextId(), job.attemptCount() + 1);
    runs.startRun(jobId, claimed.runId(), claimed.attemptNumber(), now);
    return new Claim.Started(claimed);
  }

  /** Returns the job's status if it has finished. */
  private Optional<JobStatus> finishedStatus(JobId jobId) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> runs.lockForRun(jobId).map(StoredJob::status).filter(JobStatus::isTerminal)));
  }

  /**
   * Records a temporary failure: the job runs again after the policy's delay, or, after its last
   * attempt, is failed.
   */
  private Attempt failTemporarily(ClaimedJob job, String code, String detail) {
    Optional<Duration> delay = retryPolicy.delayAfterFailedAttempt(job.attemptNumber());
    if (delay.isEmpty()) {
      fail(job, RunStatus.FAILED_TEMPORARY, code, detail, JobStatus.FAILED);
      return new Attempt(RunResult.ATTEMPTS_EXHAUSTED, JobStatus.FAILED);
    }
    fail(job, RunStatus.FAILED_TEMPORARY, code, detail, JobStatus.RETRY_PENDING);
    return new Attempt(RunResult.retryAfter(delay.get()), JobStatus.RETRY_PENDING);
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

  /** How an attempt ended: the message's fate and the job's status. */
  private record Attempt(RunResult result, JobStatus jobStatus) {

    static final Attempt FAILED = new Attempt(RunResult.FINISHED, JobStatus.FAILED);
  }

  /** What claiming a job found. */
  private sealed interface Claim {

    /** No job has this ID. */
    record UnknownJob() implements Claim {}

    /** Every attempt had been used; the job is now failed. */
    record AttemptsUsedUp(DatasetUrn dataset) implements Claim {}

    /** The job has already finished; the message is a redelivery. */
    record AlreadyFinished(JobStatus status) implements Claim {}

    /** An attempt has started. */
    record Started(ClaimedJob job) implements Claim {}
  }
}
