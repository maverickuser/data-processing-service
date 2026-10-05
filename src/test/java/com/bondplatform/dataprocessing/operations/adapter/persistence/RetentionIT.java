package com.bondplatform.dataprocessing.operations.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.operations.application.RetentionResult;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.WebApplicationContext;

/**
 * I-OPS-01 and I-OPS-02: the retention cleanup deletes review data older than a year and outbox
 * events delivered more than 30 days ago, and nothing else; a cleaned job still reports its counts
 * and error count with an empty error list.
 */
class RetentionIT extends PostgresIntegrationTest {

  private static final String COUNTS =
      "{\"sourceRecords\": 2, \"acceptedRows\": 1, \"invalidRows\": 1, \"supersededRows\": 0}";
  private static final String TABLES =
      """
      SELECT (SELECT count(*) FROM data_processing.ingestion_requests)
        + (SELECT count(*) FROM data_processing.processing_runs)
        + (SELECT count(*) FROM data_processing.source_files)
      """;

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private RejectedRecordStore rejections;
  @Autowired private RetentionCleaner cleaner;
  @Autowired private TransactionOperations transactions;
  @Autowired private WebApplicationContext context;

  @Test
  void deletesOnlyExpiredReviewDataAndDeliveredEvents() {
    UUID expiredRun = jobWithOneRejection("run_r1", "2025-01-01");
    UUID keptRun = jobWithOneRejection("run_r2", "2025-01-02");
    final UUID recentRun = jobWithOneRejection("run_r3", "2025-01-03");
    final UUID cascadedRun = jobWithOneRejection("run_r5", "2025-01-05");
    age(expiredRun, "400 days", "rejected_records", "validation_issues");
    age(keptRun, "364 days", "rejected_records", "validation_issues");
    // An expired record whose issues are newer goes, and its issues with it
    age(cascadedRun, "400 days", "rejected_records");
    final UUID oldDelivered = event("DELIVERED", "40 days", "31 days");
    final UUID newDelivered = event("DELIVERED", "40 days", "29 days");
    final UUID oldPending = event("PENDING", "400 days", null);
    final long jobRows = jdbc.sql(TABLES).query(Long.class).single();
    final long events = count("data_processing.outbox_events");

    final RetentionResult result = cleaner.clean();

    assertThat(issuesOf(expiredRun)).isZero();
    assertThat(recordsOf(expiredRun)).isZero();
    assertThat(issuesOf(cascadedRun)).isZero();
    assertThat(recordsOf(cascadedRun)).isZero();
    for (UUID kept : List.of(keptRun, recentRun)) {
      assertThat(issuesOf(kept)).isPositive();
      assertThat(recordsOf(kept)).isOne();
    }
    assertThat(eventIds()).doesNotContain(oldDelivered).contains(newDelivered, oldPending);
    assertThat(count("data_processing.outbox_events")).isEqualTo(events - 1);
    assertThat(jdbc.sql(TABLES).query(Long.class).single()).isEqualTo(jobRows);
    assertThat(result.rejectedRecords()).isEqualTo(2);
    assertThat(result.outboxEvents()).isOne();
    assertThat(result.validationIssues()).isPositive();
  }

  @Test
  void cleanedJobKeepsItsCountsAndErrorCountWithEmptyErrorList() throws Exception {
    UUID run = jobWithOneRejection("run_r4", "2025-01-04");
    String job =
        jdbc.sql(
                "SELECT ingestion_request_id::text FROM data_processing.processing_runs"
                    + " WHERE id = :id")
            .param("id", run)
            .query(String.class)
            .single();
    age(run, "400 days", "rejected_records", "validation_issues");

    cleaner.clean();

    MockMvc http = MockMvcBuilders.webAppContextSetup(context).build();
    http.perform(get("/v1/processing-jobs/{jobId}", job))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
        .andExpect(jsonPath("$.counts.invalidRows").value(1))
        .andExpect(jsonPath("$.errorCount").value(1))
        .andExpect(jsonPath("$.errors").isEmpty())
        .andExpect(jsonPath("$.hasMoreErrors").value(false));
    http.perform(get("/v1/processing-jobs/{jobId}/errors", job))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  /** Completes a BSE job whose run stored one rejected row with its issues; returns the run. */
  private UUID jobWithOneRejection(String fetchRun, String tradeDate) {
    Map<String, Object> event = SubmissionEvents.bse(fetchRun, tradeDate);
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    UUID run = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          assertThat(jobRuns.lockForRun(job)).isPresent();
          jobRuns.startRun(job, run, 1, Instant.now());
        });
    rejections.saveAll(
        new CanonicalRun(
            job,
            1,
            GoldenBhavcopy.RUN.tradeDate(),
            GoldenBhavcopy.RUN.sourceBucket(),
            GoldenBhavcopy.RUN.sourceKey(),
            GoldenBhavcopy.RUN.sourceContractVersion(),
            GoldenBhavcopy.RUN.mappingContractVersion()),
        run,
        List.of(RejectedRow.of(GoldenBhavcopy.rows().get(5), "isin", 1)));
    jobRuns.completeRun(
        job, run, 1, new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, COUNTS, 1), Instant.now());
    return run;
  }

  /** Makes a run's rows in the given tables look created the given interval ago. */
  private void age(UUID run, String interval, String... tables) {
    for (String table : tables) {
      jdbc.sql(
              "UPDATE data_processing."
                  + table
                  + " SET created_at = now() - CAST(:age AS INTERVAL)"
                  + " WHERE processing_run_id = :run")
          .param("age", interval)
          .param("run", run)
          .update();
    }
  }

  private UUID event(String status, String createdAgo, @Nullable String deliveredAgo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO data_processing.outbox_events
              (id, destination, payload, status, next_attempt_at, created_at, delivered_at)
            VALUES (:id, 'SECURITY_DETAILS', CAST('{}' AS JSONB), :status,
              now() - CAST(:created AS INTERVAL), now() - CAST(:created AS INTERVAL),
              now() - CAST(:delivered AS INTERVAL))
            """)
        .param("id", id)
        .param("status", status)
        .param("created", createdAgo)
        .param("delivered", deliveredAgo, Types.VARCHAR)
        .update();
    return id;
  }

  private long issuesOf(UUID run) {
    return runRows("validation_issues", run);
  }

  private long recordsOf(UUID run) {
    return runRows("rejected_records", run);
  }

  private long runRows(String table, UUID run) {
    return jdbc.sql(
            "SELECT count(*) FROM data_processing." + table + " WHERE processing_run_id = :run")
        .param("run", run)
        .query(Long.class)
        .single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private List<UUID> eventIds() {
    return jdbc.sql("SELECT id FROM data_processing.outbox_events").query(UUID.class).list();
  }
}
