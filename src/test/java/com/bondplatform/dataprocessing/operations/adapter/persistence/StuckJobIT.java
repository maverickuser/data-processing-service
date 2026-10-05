package com.bondplatform.dataprocessing.operations.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/**
 * I-OPS-04, stuck jobs (LLD section 23.3): the sweeper fails an unfinished job without progress for
 * an hour, and one whose final attempt has run past the visibility timeout; it leaves every other
 * job alone. Ages are set well clear of each limit, as the database's clock and the JVM's may
 * differ slightly.
 */
class StuckJobIT extends PostgresIntegrationTest {

  private static final String COUNTS =
      "{\"sourceRecords\": 1, \"acceptedRows\": 1, \"invalidRows\": 0, \"supersededRows\": 0}";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private StuckJobFailer failer;
  @Autowired private TransactionOperations transactions;

  private int runs;

  @Test
  void failsOnlyJobsThatStoppedMakingProgress() {
    final JobId neverRun = submitted("2 hours");
    final JobId recentlyQueued = submitted("30 minutes");
    JobId retryLost = submitted("3 hours");
    endTemporarily(retryLost, start(retryLost, 1, "2 hours"), "2 hours");
    // An attempt that began long ago but ended recently is progress
    JobId retryJustEnded = submitted("3 hours");
    endTemporarily(retryJustEnded, start(retryJustEnded, 1, "2 hours"), "10 minutes");
    JobId finalAttemptDied = submitted("40 minutes");
    final UUID deadRun = start(finalAttemptDied, 3, "20 minutes");
    JobId earlierAttemptRunning = submitted("40 minutes");
    start(earlierAttemptRunning, 1, "20 minutes");
    JobId finalAttemptRunning = submitted("40 minutes");
    start(finalAttemptRunning, 3, "10 minutes");
    JobId completed = submitted("3 hours");
    jobRuns.completeRun(
        completed,
        start(completed, 1, "3 hours"),
        1,
        new JobOutcome(JobStatus.COMPLETED, COUNTS, 0),
        Instant.now());
    jdbc.sql(
            "UPDATE data_processing.ingestion_requests"
                + " SET completed_at = now() - INTERVAL '3 hours' WHERE id = :id")
        .param("id", completed.value())
        .update();

    assertThat(failer.failStuckJobs()).isEqualTo(3);

    assertThat(status(neverRun)).isEqualTo("FAILED");
    assertThat(status(retryLost)).isEqualTo("FAILED");
    assertThat(status(finalAttemptDied)).isEqualTo("FAILED");
    assertThat(status(recentlyQueued)).isEqualTo("QUEUED");
    assertThat(status(retryJustEnded)).isEqualTo("RETRY_PENDING");
    assertThat(status(earlierAttemptRunning)).isEqualTo("PROCESSING");
    assertThat(status(finalAttemptRunning)).isEqualTo("PROCESSING");
    assertThat(status(completed)).isEqualTo("COMPLETED");
    assertThat(run(deadRun))
        .containsEntry("status", "FAILED_TEMPORARY")
        .containsEntry("failure_code", "ATTEMPT_ABANDONED");
    assertThat(
            jdbc.sql(
                    "SELECT failure_code FROM data_processing.processing_runs"
                        + " WHERE ingestion_request_id = :id")
                .param("id", retryLost.value())
                .query(String.class)
                .single())
        .as("an ended run keeps its own failure")
        .isEqualTo("SOURCE_UNAVAILABLE");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM data_processing.ingestion_requests"
                        + " WHERE status = 'FAILED' AND completed_at IS NULL")
                .query(Long.class)
                .single())
        .isZero();

    assertThat(failer.failStuckJobs()).as("a failed job is not failed again").isZero();
  }

  /** Admits a job and makes it look submitted the given interval ago. */
  private JobId submitted(String ago) {
    Map<String, Object> event = SubmissionEvents.nsdl("run_4" + runs++, "INE121A07QY9");
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    jdbc.sql(
            "UPDATE data_processing.ingestion_requests"
                + " SET submitted_at = now() - CAST(:ago AS INTERVAL) WHERE id = :id")
        .param("ago", ago)
        .param("id", job.value())
        .update();
    return job;
  }

  /** Starts the given attempt and makes it look started the given interval ago. */
  private UUID start(JobId job, int attempt, String ago) {
    UUID run = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          assertThat(jobRuns.lockForRun(job)).isPresent();
          jobRuns.startRun(job, run, attempt, Instant.now());
        });
    jdbc.sql(
            "UPDATE data_processing.processing_runs"
                + " SET started_at = now() - CAST(:ago AS INTERVAL) WHERE id = :id")
        .param("ago", ago)
        .param("id", run)
        .update();
    return run;
  }

  /** Ends the attempt as a temporary failure the given interval ago, leaving the job to retry. */
  private void endTemporarily(JobId job, UUID run, String ago) {
    jobRuns.failRun(
        job,
        run,
        1,
        RunStatus.FAILED_TEMPORARY,
        "SOURCE_UNAVAILABLE",
        "S3 could not be read: throttled",
        JobStatus.RETRY_PENDING,
        Instant.now());
    jdbc.sql(
            "UPDATE data_processing.processing_runs"
                + " SET completed_at = now() - CAST(:ago AS INTERVAL) WHERE id = :id")
        .param("ago", ago)
        .param("id", run)
        .update();
  }

  private String status(JobId job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
  }

  private Map<String, Object> run(UUID run) {
    return jdbc.sql(
            "SELECT status, failure_code FROM data_processing.processing_runs WHERE id = :id")
        .param("id", run)
        .query()
        .singleRow();
  }
}
