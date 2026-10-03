package com.bondplatform.dataprocessing.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Test case U-JOB-02 and the attempt life cycle, with an in-memory store. */
class RunJobTest {

  private static final Instant NOW = Instant.parse("2026-09-27T14:40:00Z");
  private static final DatasetUrn NSDL = new DatasetUrn("urn:bond-platform:dataset:nsdl-security");
  private static final JobId JOB =
      new JobId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
  private static final JobOutcome COMPLETED =
      new JobOutcome(JobStatus.COMPLETED, "{\"securities\":1}", 0);

  private final InMemoryRuns runs = new InMemoryRuns();
  private final AtomicLong nextId = new AtomicLong(1);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final JobCompletion completion = new JobCompletion(runs, clock);
  private final FakeHandler handler = new FakeHandler();
  private final RunJob runJob =
      new RunJob(
          runs,
          List.of(handler),
          new DirectTransactions(),
          clock,
          () -> new UUID(0, nextId.getAndIncrement()));

  @Test
  void runsTheHandlerInNewAttemptThatFinishesTheJob() {
    runs.add(JobStatus.QUEUED, 0);
    handler.behaviour = job -> completion.complete(job, COMPLETED);

    assertThat(runJob.run(JOB)).isEqualTo(RunResult.FINISHED);

    assertThat(handler.claimed)
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.attemptNumber()).isEqualTo(1);
              assertThat(job.runId()).isEqualTo(new UUID(0, 1));
              assertThat(job.job().dataset()).isEqualTo(NSDL);
            });
    assertThat(runs.status).isEqualTo(JobStatus.COMPLETED);
    assertThat(runs.runs).containsExactly(new Run(new UUID(0, 1), 1, RunStatus.SUCCEEDED, null));
    assertThat(runs.outcome).isEqualTo(COMPLETED);
  }

  // U-JOB-02
  @Test
  void messageForFinishedJobDoesNothing() {
    runs.add(JobStatus.COMPLETED, 1);

    assertThat(runJob.run(JOB)).isEqualTo(RunResult.ALREADY_FINISHED);

    assertThat(handler.claimed).isEmpty();
    assertThat(runs.runs).isEmpty();
    assertThat(runs.attemptCount).isEqualTo(1);
  }

  @Test
  void messageForUnknownJobIsDropped() {
    assertThat(runJob.run(JOB)).isEqualTo(RunResult.UNKNOWN_JOB);
    assertThat(RunResult.UNKNOWN_JOB.acknowledgesMessage()).isTrue();
    assertThat(handler.claimed).isEmpty();
  }

  @Test
  void runLeftRunningByDeadInvocationIsEndedWhenTheNextAttemptStarts() {
    runs.add(JobStatus.PROCESSING, 1);
    runs.runs.add(new Run(new UUID(9, 9), 1, RunStatus.RUNNING, null));
    handler.behaviour = job -> completion.complete(job, COMPLETED);

    runJob.run(JOB);

    assertThat(runs.runs)
        .containsExactly(
            new Run(new UUID(9, 9), 1, RunStatus.FAILED_TEMPORARY, "ATTEMPT_ABANDONED"),
            new Run(new UUID(0, 1), 2, RunStatus.SUCCEEDED, null));
    assertThat(runs.attemptCount).isEqualTo(2);
  }

  @Test
  void permanentFailureFailsTheJobAndAcknowledgesTheMessage() {
    runs.add(JobStatus.QUEUED, 0);
    handler.behaviour =
        job -> {
          throw new PermanentFailureException("SOURCE_NOT_FOUND", "The manifest does not exist");
        };

    assertThat(runJob.run(JOB)).isEqualTo(RunResult.FINISHED);

    assertThat(runs.status).isEqualTo(JobStatus.FAILED);
    assertThat(runs.runs)
        .containsExactly(
            new Run(new UUID(0, 1), 1, RunStatus.FAILED_PERMANENT, "SOURCE_NOT_FOUND"));
    assertThat(runs.lastDetail).isEqualTo("The manifest does not exist");
  }

  @Test
  void temporaryFailureLeavesTheJobToRunAgain() {
    runs.add(JobStatus.QUEUED, 0);
    handler.behaviour =
        job -> {
          throw new TemporaryFailureException(
              "SOURCE_UNAVAILABLE", "S3 did not answer", new IllegalStateException());
        };

    RunResult result = runJob.run(JOB);

    assertThat(result).isEqualTo(RunResult.RETRY_LATER);
    assertThat(result.acknowledgesMessage()).isFalse();
    assertThat(runs.status).isEqualTo(JobStatus.RETRY_PENDING);
    assertThat(runs.runs.get(0).status()).isEqualTo(RunStatus.FAILED_TEMPORARY);
  }

  @Test
  void unexpectedFailureIsTemporaryAndStoresOnlyItsType() {
    runs.add(JobStatus.QUEUED, 0);
    handler.behaviour =
        job -> {
          throw new IllegalArgumentException("password=secret");
        };

    assertThat(runJob.run(JOB)).isEqualTo(RunResult.RETRY_LATER);

    assertThat(runs.runs.get(0).code()).isEqualTo("UNEXPECTED_FAILURE");
    assertThat(runs.lastDetail).isEqualTo("java.lang.IllegalArgumentException");
  }

  @Test
  void handlerThatDoesNotFinishTheJobFailsIt() {
    runs.add(JobStatus.QUEUED, 0);
    handler.behaviour = job -> {};

    assertThat(runJob.run(JOB)).isEqualTo(RunResult.FINISHED);

    assertThat(runs.status).isEqualTo(JobStatus.FAILED);
    assertThat(runs.runs.get(0).code()).isEqualTo("JOB_NOT_FINISHED");
  }

  @Test
  void jobOfDatasetWithoutHandlerIsDefect() {
    runs.add(JobStatus.QUEUED, 0);
    RunJob withoutHandlers =
        new RunJob(runs, List.of(), new DirectTransactions(), clock, () -> new UUID(0, 1));

    assertThatIllegalStateException()
        .isThrownBy(() -> withoutHandlers.run(JOB))
        .withMessageContaining("nsdl-security");
  }

  @Test
  void twoHandlersForOneDatasetAreRefused() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                new RunJob(
                    runs,
                    List.of(new FakeHandler(), new FakeHandler()),
                    new DirectTransactions(),
                    clock,
                    UUID::randomUUID));
  }

  @Test
  void logContextNamesTheJobAndAttemptOnlyWhileRunning() {
    runs.add(JobStatus.QUEUED, 0);
    Map<String, String> seen = new LinkedHashMap<>();
    handler.behaviour =
        job -> {
          seen.putAll(MDC.getCopyOfContextMap());
          completion.complete(job, COMPLETED);
        };

    runJob.run(JOB);

    assertThat(seen).containsEntry("jobId", JOB.toString()).containsEntry("attemptNumber", "1");
    assertThat(MDC.get("jobId")).isNull();
    assertThat(MDC.get("attemptNumber")).isNull();
  }

  private record Run(UUID id, int attemptNumber, RunStatus status, @Nullable String code) {}

  /** Runs callbacks directly, standing in for a transaction manager. */
  private static final class DirectTransactions implements TransactionOperations {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return action.doInTransaction(new SimpleTransactionStatus());
    }
  }

  /** A handler whose behaviour each test sets. */
  private static final class FakeHandler implements DatasetHandler {

    private final List<ClaimedJob> claimed = new ArrayList<>();
    private Consumer<ClaimedJob> behaviour = job -> {};

    @Override
    public DatasetUrn dataset() {
      return NSDL;
    }

    @Override
    public void process(ClaimedJob job) {
      claimed.add(job);
      behaviour.accept(job);
    }
  }

  /** One job and its runs, with the same guards as the database. */
  private static final class InMemoryRuns implements JobRunRepository {

    private final List<Run> runs = new ArrayList<>();
    private boolean exists;
    private JobStatus status = JobStatus.QUEUED;
    private int attemptCount;
    private @Nullable JobOutcome outcome;
    private @Nullable String lastDetail;

    void add(JobStatus initial, int attempts) {
      exists = true;
      status = initial;
      attemptCount = attempts;
    }

    @Override
    public Optional<StoredJob> lockForRun(JobId id) {
      if (!exists) {
        return Optional.empty();
      }
      return Optional.of(
          new StoredJob(
              id,
              status,
              attemptCount,
              NSDL,
              "isin/INE121A07QY9",
              new OrderingGroup("isin:INE121A07QY9"),
              "{\"isin_code\":\"INE121A07QY9\"}",
              new ManifestLocation("bucket", "runs/run_202/manifest.json", null),
              "sha256:fingerprint",
              new PinnedContractVersions(
                  new ContractId("nsdl-security-json", "v1"),
                  "sha256:s",
                  new ContractId("nsdl-security-mapping", "v1"),
                  "sha256:m")));
    }

    @Override
    public void endAbandonedRuns(JobId id, String code, Instant now) {
      runs.replaceAll(
          run ->
              run.status() == RunStatus.RUNNING
                  ? new Run(run.id(), run.attemptNumber(), RunStatus.FAILED_TEMPORARY, code)
                  : run);
    }

    @Override
    public void startRun(JobId id, UUID runId, int attemptNumber, Instant now) {
      status = JobStatus.PROCESSING;
      attemptCount = attemptNumber;
      runs.add(new Run(runId, attemptNumber, RunStatus.RUNNING, null));
    }

    @Override
    public void completeRun(JobId id, UUID runId, JobOutcome jobOutcome, Instant now) {
      status = jobOutcome.status();
      outcome = jobOutcome;
      end(runId, RunStatus.SUCCEEDED, null);
    }

    @Override
    public void failRun(
        JobId id,
        UUID runId,
        RunStatus runStatus,
        String code,
        String detail,
        JobStatus jobStatus,
        Instant now) {
      status = jobStatus;
      lastDetail = detail;
      end(runId, runStatus, code);
    }

    private void end(UUID runId, RunStatus runStatus, @Nullable String code) {
      runs.replaceAll(
          run ->
              run.id().equals(runId)
                  ? new Run(run.id(), run.attemptNumber(), runStatus, code)
                  : run);
    }
  }
}
