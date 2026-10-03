package com.bondplatform.dataprocessing.job.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.application.DatasetHandler;
import com.bondplatform.dataprocessing.job.application.IngestionRequestRepository;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.application.RunJob;
import com.bondplatform.dataprocessing.job.application.RunResult;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionOperations;

/** Job attempts against PostgreSQL, with a dataset handler whose behaviour each test sets. */
@Import(JobRunIT.TestHandlerConfiguration.class)
class JobRunIT extends PostgresIntegrationTest {

  private static final JobOutcome COMPLETED = new JobOutcome(JobStatus.COMPLETED, "{\"n\": 1}", 0);

  @Autowired private IngestionRequestRepository requests;
  @Autowired private RunJob runJob;
  @Autowired private JobCompletion completion;
  @Autowired private TransactionOperations transactions;
  @Autowired private SettableHandler handler;

  private JobId job;

  @BeforeEach
  void storeQueuedJob() {
    UUID id = UUID.randomUUID();
    requests.insertIfAbsent(IngestionRequests.nsdl(id, "run_" + id, "event-" + id));
    job = new JobId(id);
  }

  @Test
  void finishedAttemptRecordsJobOutcomeAndSucceededRun() {
    handler.behaviour = claimed -> publishAndComplete(claimed, COMPLETED);

    assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);

    assertThat(jobRow())
        .containsEntry("status", "COMPLETED")
        .containsEntry("attempt_count", 1)
        .containsEntry("error_count", 0)
        .containsEntry("counts", "{\"n\": 1}")
        .containsEntry("started", true)
        .containsEntry("completed", true);
    assertThat(runRows()).containsExactly(Map.of("attempt_number", 1, "status", "SUCCEEDED"));
  }

  // I-OPS-03
  @Test
  void invocationThatDiesMidRunLeavesNoBusinessDataAndTheNextAttemptStartsNewRun() {
    handler.behaviour =
        claimed ->
            transactions.executeWithoutResult(
                status -> {
                  insertSecurity();
                  // The invocation dies: nothing after this point runs and nothing is recorded.
                  throw new InvocationDied();
                });
    assertThatThrownBy(() -> runJob.run(job)).isInstanceOf(InvocationDied.class);
    assertThat(jobRow()).containsEntry("status", "PROCESSING");
    assertThat(securityCount()).isZero();

    handler.behaviour = claimed -> publishAndComplete(claimed, COMPLETED);
    assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);

    assertThat(runRows())
        .containsExactly(
            Map.of("attempt_number", 1, "status", "FAILED_TEMPORARY"),
            Map.of("attempt_number", 2, "status", "SUCCEEDED"));
    assertThat(
            jdbc.sql(
                    "SELECT failure_code FROM data_processing.processing_runs"
                        + " WHERE attempt_number = 1")
                .query(String.class)
                .single())
        .isEqualTo("ATTEMPT_ABANDONED");
    assertThat(jobRow()).containsEntry("status", "COMPLETED").containsEntry("attempt_count", 2);
    assertThat(securityCount()).isEqualTo(1);
  }

  // U-JOB-02 against the database
  @Test
  void redeliveryAfterTheJobFinishedStartsNoRun() {
    handler.behaviour = claimed -> publishAndComplete(claimed, COMPLETED);
    runJob.run(job);

    assertThat(runJob.run(job)).isEqualTo(RunResult.ALREADY_FINISHED);

    assertThat(runRows()).hasSize(1);
    assertThat(securityCount()).isEqualTo(1);
  }

  @Test
  void permanentFailureFailsTheJobWithItsCode() {
    handler.behaviour =
        claimed -> {
          throw new PermanentFailureException("SOURCE_NOT_FOUND", "The manifest does not exist");
        };

    assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);

    assertThat(jobRow()).containsEntry("status", "FAILED").containsEntry("completed", true);
    assertThat(
            jdbc.sql(
                    "SELECT status, failure_code, failure_detail, completed_at IS NOT NULL AS ended"
                        + " FROM data_processing.processing_runs")
                .query()
                .singleRow())
        .containsEntry("status", "FAILED_PERMANENT")
        .containsEntry("failure_code", "SOURCE_NOT_FOUND")
        .containsEntry("failure_detail", "The manifest does not exist")
        .containsEntry("ended", true);
  }

  @Test
  void temporaryFailureLeavesTheJobPendingAnotherAttempt() {
    handler.behaviour =
        claimed -> {
          throw new TemporaryFailureException(
              "SOURCE_UNAVAILABLE", "S3 did not answer", new IllegalStateException());
        };

    assertThat(runJob.run(job)).isEqualTo(RunResult.RETRY_LATER);

    assertThat(jobRow()).containsEntry("status", "RETRY_PENDING").containsEntry("completed", false);
    assertThat(runRows())
        .containsExactly(Map.of("attempt_number", 1, "status", "FAILED_TEMPORARY"));
  }

  @Test
  void outcomeCanOnlyBeRecordedInsideTheTransactionThatPublishes() {
    handler.behaviour =
        claimed ->
            assertThatThrownBy(() -> completion.complete(claimed, COMPLETED))
                .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);

    assertThat(jobRow()).containsEntry("status", "FAILED");
    assertThat(
            jdbc.sql("SELECT failure_code FROM data_processing.processing_runs")
                .query(String.class)
                .single())
        .isEqualTo("JOB_NOT_FINISHED");
  }

  // Review finding R-2: an attempt still alive after another took over must not change the job.
  @Test
  void attemptThatWasTakenOverCanNeitherCompleteNorFailTheJob() {
    handler.behaviour =
        first -> {
          handler.behaviour = second -> publishAndComplete(second, COMPLETED);
          assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);
          // The first attempt, still alive, now tries to finish the job.
          publishAndComplete(first, new JobOutcome(JobStatus.FAILED, "{}", 0));
        };

    assertThat(runJob.run(job)).isEqualTo(RunResult.RETRY_LATER);

    assertThat(jobRow()).containsEntry("status", "COMPLETED").containsEntry("attempt_count", 2);
    assertThat(runRows())
        .containsExactly(
            Map.of("attempt_number", 1, "status", "FAILED_TEMPORARY"),
            Map.of("attempt_number", 2, "status", "SUCCEEDED"));
    assertThat(securityCount()).isEqualTo(1);
  }

  @Test
  void permanentFailureOfAttemptThatWasTakenOverLeavesTheJobAlone() {
    handler.behaviour =
        first -> {
          handler.behaviour = second -> publishAndComplete(second, COMPLETED);
          runJob.run(job);
          throw new PermanentFailureException("SOURCE_NOT_FOUND", "late failure");
        };

    assertThat(runJob.run(job)).isEqualTo(RunResult.FINISHED);

    assertThat(jobRow()).containsEntry("status", "COMPLETED").containsEntry("completed", true);
    assertThat(runRows())
        .containsExactly(
            Map.of("attempt_number", 1, "status", "FAILED_TEMPORARY"),
            Map.of("attempt_number", 2, "status", "SUCCEEDED"));
  }

  private void publishAndComplete(ClaimedJob claimed, JobOutcome outcome) {
    transactions.executeWithoutResult(
        status -> {
          insertSecurity();
          completion.complete(claimed, outcome);
        });
  }

  private void insertSecurity() {
    jdbc.sql(
            """
            INSERT INTO securities_data.securities (isin, created_at, updated_at)
            VALUES ('INE121A07QY9', now(), now()) ON CONFLICT DO NOTHING
            """)
        .update();
  }

  private long securityCount() {
    return jdbc.sql("SELECT count(*) FROM securities_data.securities").query(Long.class).single();
  }

  private Map<String, Object> jobRow() {
    return jdbc.sql(
            """
            SELECT status, attempt_count, error_count, counts::text AS counts,
              started_at IS NOT NULL AS started, completed_at IS NOT NULL AS completed
            FROM data_processing.ingestion_requests WHERE id = :id
            """)
        .param("id", job.value())
        .query()
        .singleRow();
  }

  private List<Map<String, Object>> runRows() {
    return jdbc.sql(
            "SELECT attempt_number, status FROM data_processing.processing_runs"
                + " ORDER BY attempt_number")
        .query()
        .listOfRows();
  }

  /** Stands in for the death of the Lambda invocation: an error nothing catches. */
  private static final class InvocationDied extends Error {
    private static final long serialVersionUID = 1L;
  }

  /** A dataset handler for NSDL jobs whose behaviour each test sets. */
  static final class SettableHandler implements DatasetHandler {

    private Consumer<ClaimedJob> behaviour = claimed -> {};

    @Override
    public DatasetUrn dataset() {
      return new DatasetUrn("urn:bond-platform:dataset:nsdl-security");
    }

    @Override
    public void process(ClaimedJob claimed) {
      behaviour.accept(claimed);
    }
  }

  /** Adds the settable handler to the application. */
  @TestConfiguration(proxyBeanMethods = false)
  static class TestHandlerConfiguration {

    @Bean
    SettableHandler settableHandler() {
      return new SettableHandler();
    }
  }
}
