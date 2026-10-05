package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.SourceFileRepository;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** I-READ-01: job status for every status, with CSV and JSON counts, read from PostgreSQL. */
class JobStatusIT extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final String CSV_COUNTS =
      "{\"sourceRecords\": 2, \"acceptedRows\": 1, \"invalidRows\": 1, \"supersededRows\": 0}";
  private static final String JSON_COUNTS =
      "{\"filesListed\": 1, \"filesProcessed\": 1, \"filesSkipped\": 0,"
          + " \"scalarFieldsChanged\": 0, \"collectionEntriesAppended\": 0, \"fieldsRejected\": 2}";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private SourceFileRepository sourceFiles;
  @Autowired private JsonRejectionStore rejections;
  @Autowired private TransactionOperations transactions;
  @Autowired private JobStatusController controller;
  @Autowired private JsonMapper json;

  @Test
  void queuedJobHasNothingYet() {
    JobId job = bse("run_q");

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("QUEUED");
    assertThat(body.get("dataset").asString())
        .isEqualTo("urn:bond-platform:dataset:bse-debt-trades");
    assertThat(body.get("attemptCount").asInt()).isZero();
    assertThat(body.get("submittedAt").asString()).isNotEmpty();
    assertThat(body.get("startedAt").isNull()).isTrue();
    assertThat(body.get("completedAt").isNull()).isTrue();
    assertThat(body.get("counts").isNull()).isTrue();
    assertThat(body.get("errorCount").asInt()).isZero();
    assertThat(body.get("errors")).isEmpty();
    assertThat(body.get("hasMoreErrors").asBoolean()).isFalse();
    assertThat(body.get("errorsUrl").asString())
        .isEqualTo("/v1/processing-jobs/" + job + "/errors");
  }

  @Test
  void runningJobHasStartedButShowsNoCounts() {
    JobId job = bse("run_p");
    start(job, 1);

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("PROCESSING");
    assertThat(body.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(body.get("startedAt").asString()).isEqualTo("2026-10-05T10:00:00Z");
    assertThat(body.get("counts").isNull()).isTrue();
  }

  @Test
  void completedCsvJobShowsItsCsvCounts() {
    JobId job = bse("run_c");
    UUID run = start(job, 1);
    jobRuns.completeRun(job, run, 1, new JobOutcome(JobStatus.COMPLETED, CSV_COUNTS, 0), NOW);

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(body.get("completedAt").asString()).isEqualTo("2026-10-05T10:00:00Z");
    assertThat(json.treeToValue(body.get("counts"), Map.class))
        .isEqualTo(
            Map.of("sourceRecords", 2, "acceptedRows", 1, "invalidRows", 1, "supersededRows", 0));
    assertThat(body.get("errorCount").asInt()).isZero();
  }

  // Errors come from the final run only (PR 35d review AL-1), with typed or omitted raw values
  @Test
  void jsonJobWithErrorsShowsJsonCountsAndOnlyItsFinalRunsErrors() {
    JobId job = nsdl("run_j");
    UUID first = start(job, 1);
    store(job, first, 1);
    jobRuns.failRun(
        job,
        first,
        1,
        RunStatus.FAILED_TEMPORARY,
        "SOURCE_UNAVAILABLE",
        "S3 timed out.",
        JobStatus.RETRY_PENDING,
        NOW);
    UUID second = start(job, 2);
    store(job, second, 2);
    jobRuns.completeRun(
        job, second, 2, new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, JSON_COUNTS, 4), NOW);

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(body.at("/counts/filesListed").asInt()).isEqualTo(1);
    assertThat(body.at("/counts/fieldsRejected").asInt()).isEqualTo(2);
    assertThat(body.get("errorCount").asInt()).isEqualTo(4);
    assertThat(body.get("errors")).hasSize(4);
    assertThat(body.get("hasMoreErrors").asBoolean()).isFalse();
    JsonNode section = body.at("/errors/0");
    assertThat(section.get("code").asString()).isEqualTo("INVALID_TYPE");
    assertThat(section.get("path").asString()).isEqualTo("$.currentRatings");
    assertThat(section.has("rawValue")).isFalse();
    assertThat(section.get("rawValueTruncated").asBoolean()).isFalse();
    JsonNode rate = body.at("/errors/1");
    assertThat(rate.get("field").asString()).isEqualTo("coupon_rate");
    assertThat(rate.get("rawValue").asString()).isEqualTo("abc");
    assertThat(rate.get("sourceFile").asString()).isEqualTo("INE831R08076_instrument-details.json");
    assertThat(rate.get("isin").asString()).isEqualTo("INE831R08076");
    assertThat(rate.get("recordNumber").isNull()).isTrue();
    JsonNode ignored = body.at("/errors/3");
    assertThat(ignored.get("rawValue").isNumber()).isTrue();
    assertThat(ignored.get("rawValue").decimalValue()).isEqualByComparingTo("100");
    assertThat(ignored.get("actionTaken").asString()).isEqualTo("Ignored supplied coverage.");
  }

  // PR 29b review AC-3: the final run's failure is an error, first, with the job's one file
  @Test
  void failedJobShowsItsFailureAsAnErrorAndNullCounts() {
    JobId job = bse("run_f");
    sourceFiles.saveAll(
        job,
        List.of(
            new ManifestFile(
                "debt-bhavcopy",
                "data-fetch-service-artifacts",
                "runs/run_f/raw/debt-bhavcopy/1/BSE_fgroup01012026.csv",
                SourceFormat.CSV,
                "a".repeat(64),
                10,
                null)));
    UUID run = start(job, 1);
    jobRuns.failRun(
        job,
        run,
        1,
        RunStatus.FAILED_PERMANENT,
        "CHECKSUM_MISMATCH",
        "The file's sha256 differs from the manifest.",
        JobStatus.FAILED,
        NOW);

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("FAILED");
    assertThat(body.get("counts").get("sourceRecords").isNull()).isTrue();
    assertThat(body.get("errorCount").asInt()).isEqualTo(1);
    JsonNode failure = body.at("/errors/0");
    assertThat(failure.get("errorId").asString()).isEqualTo(run.toString());
    assertThat(failure.get("code").asString()).isEqualTo("CHECKSUM_MISMATCH");
    assertThat(failure.get("message").asString())
        .isEqualTo("The file's sha256 differs from the manifest.");
    assertThat(failure.get("sourceFile").asString()).isEqualTo("BSE_fgroup01012026.csv");
    assertThat(failure.get("field").isNull()).isTrue();
    assertThat(failure.toString()).doesNotContain("runs/run_f").doesNotContain("artifacts");
  }

  @Test
  void unknownJobIsNotFound() {
    assertThatThrownBy(() -> controller.get(UUID.randomUUID().toString()))
        .isInstanceOf(ApiProblemException.class);
  }

  private JsonNode body(JobId job) {
    return json.readTree(json.writeValueAsString(controller.get(job.toString())));
  }

  private JobId bse(String runId) {
    Map<String, Object> event = SubmissionEvents.bse(runId, "2026-01-01");
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
  }

  private JobId nsdl(String runId) {
    Map<String, Object> event = SubmissionEvents.nsdl(runId, "INE831R08076");
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
  }

  private UUID start(JobId job, int attempt) {
    UUID run = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          assertThat(jobRuns.lockForRun(job)).isPresent();
          jobRuns.startRun(job, run, attempt, NOW);
        });
    return run;
  }

  /** Stores the four rejections of the JSON evidence fixture for the run. */
  private void store(JobId job, UUID run, int attempt) {
    rejections.saveJson(
        new JsonCanonicalRun(
            job,
            attempt,
            Isin.of("INE831R08076"),
            "nsdl-security-json-v1",
            "nsdl-security-mapping-v1"),
        run,
        List.of(JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED))));
  }
}
