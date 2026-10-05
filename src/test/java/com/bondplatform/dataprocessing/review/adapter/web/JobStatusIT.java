package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordBuffer;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.WebApplicationContext;
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
  @Autowired private RejectedRecordStore rejectedRows;
  @Autowired private WebApplicationContext context;

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
    JsonNode section = error(body, "path", "$.currentRatings");
    assertThat(section.get("code").asString()).isEqualTo("INVALID_TYPE");
    assertThat(section.get("path").asString()).isEqualTo("$.currentRatings");
    assertThat(section.has("rawValue")).isFalse();
    assertThat(section.get("rawValueTruncated").asBoolean()).isFalse();
    JsonNode rate = error(body, "field", "coupon_rate");
    assertThat(rate.get("field").asString()).isEqualTo("coupon_rate");
    assertThat(rate.get("rawValue").asString()).isEqualTo("abc");
    assertThat(rate.get("sourceFile").asString()).isEqualTo("INE831R08076_instrument-details.json");
    assertThat(rate.get("isin").asString()).isEqualTo("INE831R08076");
    assertThat(rate.get("recordNumber").isNull()).isTrue();
    JsonNode ignored = error(body, "code", "CONFLICTING_COLLATERAL_DATA");
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

  // PR 36 review AM-2: an earlier failed run of a job about to retry shows nothing yet
  @Test
  void retryPendingJobShowsNoCountsOrErrors() {
    JobId job = nsdl("run_r");
    UUID run = start(job, 1);
    store(job, run, 1, JsonEvidence.WITH_ERRORS);
    jobRuns.failRun(
        job,
        run,
        1,
        RunStatus.FAILED_TEMPORARY,
        "SOURCE_UNAVAILABLE",
        "S3 timed out.",
        JobStatus.RETRY_PENDING,
        NOW);

    JsonNode body = body(job);

    assertThat(body.get("status").asString()).isEqualTo("RETRY_PENDING");
    assertThat(body.get("counts").isNull()).isTrue();
    assertThat(body.get("completedAt").isNull()).isTrue();
    assertThat(body.get("errorCount").asInt()).isZero();
    assertThat(body.get("errors")).isEmpty();
  }

  // AM-2: a CSV job that failed after storing rejected rows; failure first, then rows by record
  @Test
  void failedCsvJobPreviewsItsFailureThenItsRowsAndHasMore() {
    JobId job = bse("run_g");
    UUID run = start(job, 1);
    CanonicalRun golden = GoldenBhavcopy.RUN;
    RejectedRecordBuffer buffer =
        new RejectedRecordBuffer(
            rejectedRows,
            new CanonicalRun(
                job,
                1,
                golden.tradeDate(),
                golden.sourceBucket(),
                golden.sourceKey(),
                golden.sourceContractVersion(),
                golden.mappingContractVersion()),
            run,
            "isin");
    GoldenBhavcopy.rows().forEach(buffer::add);
    buffer.flush();
    jobRuns.failRun(
        job,
        run,
        1,
        RunStatus.FAILED_PERMANENT,
        "MALFORMED_CSV",
        "Record 12 has 4 cells, but the header has 11",
        JobStatus.FAILED,
        NOW);

    JsonNode body = body(job);

    assertThat(body.get("errorCount").asInt()).isEqualTo(6);
    assertThat(body.get("hasMoreErrors").asBoolean()).isTrue();
    assertThat(body.get("errors")).hasSize(5);
    assertThat(body.at("/errors/0/code").asString()).isEqualTo("MALFORMED_CSV");
    assertThat(body.at("/errors/1/code").asString()).isEqualTo("DUPLICATE_ISIN_SUPERSEDED");
    assertThat(body.at("/errors/1/recordNumber").asInt()).isEqualTo(3);
    assertThat(body.at("/errors/1/sourceFile").asString()).isEqualTo("BSE_fgroup01012026.csv");
    assertThat(body.at("/errors/1/isin").asString()).isEqualTo("INE002B08CD2");
    assertThat(body.at("/counts/sourceRecords").isNull()).isTrue();
  }

  // AM-2: every raw value type read back from JSONB, and a long text cut to 1,000 characters
  @Test
  void rawValuesKeepTheirTypeAndLongTextIsCut() {
    JobId job = nsdl("run_t");
    UUID run = start(job, 1);
    String longDate = "x".repeat(1_500);
    store(
        job,
        run,
        1,
        JsonEvidence.TYPED
            .strip()
            .replaceFirst(
                "^\\{",
                "{\"instrumentsVo\": {\"instruments\": {\"allotmentDate\": \""
                    + longDate
                    + "\"}},"));
    jobRuns.completeRun(
        job, run, 1, new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, JSON_COUNTS, 5), NOW);

    JsonNode body = body(job);

    assertThat(body.get("errorCount").asInt()).isEqualTo(5);
    assertThat(error(body, "field", "issuer_name").get("rawValue").decimalValue())
        .isEqualTo(new BigDecimal("89400.00"));
    assertThat(error(body, "field", "issuer_ownership_type").get("rawValue").asString())
        .isEqualTo("{\"a\": [1, \"x\"]}");
    assertThat(error(body, "field", "coupon_type").get("rawValue").isBoolean()).isTrue();
    JsonNode date = error(body, "field", "allotment_date");
    assertThat(date.get("rawValue").asString()).isEqualTo("x".repeat(1_000));
    assertThat(date.get("rawValueTruncated").asBoolean()).isTrue();
  }

  // AM-2: through the HTTP layer, with the problem body for an unknown job
  @Test
  void httpAnswersJsonAndUnknownJobIsProblem() throws Exception {
    MockMvc http = MockMvcBuilders.webAppContextSetup(context).build();
    JobId job = bse("run_h");

    MvcResult found = http.perform(get("/v1/processing-jobs/" + job)).andReturn();
    MvcResult missing = http.perform(get("/v1/processing-jobs/" + UUID.randomUUID())).andReturn();

    assertThat(found.getResponse().getStatus()).isEqualTo(200);
    assertThat(found.getResponse().getContentType()).startsWith("application/json");
    assertThat(json.readTree(found.getResponse().getContentAsString()).get("jobId").asString())
        .isEqualTo(job.toString());
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    assertThat(missing.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode problem = json.readTree(missing.getResponse().getContentAsString());
    assertThat(problem.get("status").asInt()).isEqualTo(404);
    assertThat(problem.get("title").asString()).isEqualTo("Not Found");
    assertThat(problem.has("type")).isTrue();
  }

  @Test
  void unknownJobIsNotFound() {
    assertThatThrownBy(() -> controller.get(UUID.randomUUID().toString()))
        .isInstanceOf(ApiProblemException.class);
  }

  private static JsonNode error(JsonNode body, String property, String value) {
    for (JsonNode error : body.get("errors")) {
      if (error.has(property) && value.equals(error.get(property).asString())) {
        return error;
      }
    }
    throw new AssertionError("No error with " + property + " " + value + " in " + body);
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
    store(job, run, attempt, JsonEvidence.WITH_ERRORS);
  }

  /** Stores the rejections of one instrument-details file holding the JSON text. */
  private void store(JobId job, UUID run, int attempt, String fileJson) {
    rejections.saveJson(
        new JsonCanonicalRun(
            job,
            attempt,
            Isin.of("INE831R08076"),
            "nsdl-security-json-v1",
            "nsdl-security-mapping-v1"),
        run,
        List.of(
            JsonEvidence.read(
                fileJson,
                fileJson.equals(JsonEvidence.WITH_ERRORS)
                    ? List.of(JsonEvidence.IGNORED)
                    : List.of())));
  }
}
