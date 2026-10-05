package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.SourceFileRepository;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.WebApplicationContext;

/**
 * I-READ-06, I-READ-07 and I-READ-08: every documented read response holds to the read OpenAPI
 * document; no read response shows a bucket, object key, version ID or source URL held in the data;
 * decimals keep their scale and timestamps are UTC.
 */
class ReadContractIT extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final String ISIN = "INE831R08076";
  private static final String BUCKET = "data-fetch-service-artifacts";
  private static final String SOURCE_URL = "https://www.bseindia.com/download/BSE_fgroup.csv";
  private static final String VERSION = "3HL4kqtJlcpXroDTDmJ";
  private static final String CANONICAL_KEY = "canonical/2026/10/05/run-1.jsonl";
  private static final String CSV_KEY = "runs/run_c1/raw/debt-bhavcopy/1/BSE_fgroup01012026.csv";
  private static final String JSON_KEY = "nsdl/INE831R08076/run_201";
  private static final String JOB = "/v1/processing-jobs/{jobId}";
  private static final String ERRORS = "/v1/processing-jobs/{jobId}/errors";
  private static final String SECURITY = "/v1/securities/{isin}";
  private static final String SUMMARIES = "/v1/securities/{isin}/daily-market-summaries";
  private static final String BY_DATE = "/v1/daily-market-summaries";
  private static final String CSV_COUNTS =
      "{\"sourceRecords\": 2, \"acceptedRows\": 1, \"invalidRows\": 1, \"supersededRows\": 0}";
  private static final String JSON_COUNTS =
      "{\"filesListed\": 1, \"filesProcessed\": 1, \"filesSkipped\": 0,"
          + " \"scalarFieldsChanged\": 0, \"collectionEntriesAppended\": 0, \"fieldsRejected\": 4}";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private SourceFileRepository sourceFiles;
  @Autowired private JsonRejectionStore rejections;
  @Autowired private RejectedRecordStore csvRejections;
  @Autowired private TransactionOperations transactions;
  @Autowired private WebApplicationContext context;

  private final Set<String> covered = new TreeSet<>();
  private final List<String> bodies = new ArrayList<>();

  @Test
  void everyReadResponseHoldsToTheContractAndShowsNoStorageLocation() throws Exception {
    MockMvc http = MockMvcBuilders.webAppContextSetup(context).build();
    JobId csvJob = completedCsvJob();
    JobId jsonJob = jsonJobWithErrors();
    JobId failedJob = failedCsvJob();
    JobId waitingJob = submitted(SubmissionEvents.bse("run_c4", "2026-01-04"));
    String unknownJob = UUID.randomUUID().toString();
    security();

    for (JobId each : List.of(csvJob, jsonJob, failedJob, waitingJob)) {
      check(http, JOB, 200, get(JOB, each.toString()));
    }
    check(http, JOB, 404, get(JOB, unknownJob));
    check(http, ERRORS, 200, get(ERRORS, csvJob.toString()));
    check(http, ERRORS, 200, get(ERRORS, jsonJob.toString()));
    check(http, ERRORS, 200, get(ERRORS, jsonJob.toString()).param("isin", ISIN));
    check(http, ERRORS, 200, get(ERRORS, failedJob.toString()));
    check(http, ERRORS, 400, get(ERRORS, jsonJob.toString()).param("pageToken", "changed"));
    check(http, ERRORS, 404, get(ERRORS, unknownJob));
    check(http, SECURITY, 200, get(SECURITY, ISIN));
    check(http, SECURITY, 200, get(SECURITY, "INE000000001"));
    check(http, SECURITY, 404, get(SECURITY, "INE999999999"));
    check(http, SUMMARIES, 200, get(SUMMARIES, ISIN));
    check(http, SUMMARIES, 200, get(SUMMARIES, "INE000000001"));
    check(http, SUMMARIES, 400, get(SUMMARIES, ISIN).param("fromDate", "2026-02-30"));
    check(http, SUMMARIES, 404, get(SUMMARIES, "INE999999999"));
    check(http, BY_DATE, 200, get(BY_DATE).param("tradeDate", "2026-01-01"));
    check(
        http,
        BY_DATE,
        200,
        get(BY_DATE).param("tradeDate", "2026-01-01").param("exchangeName", "NSE"));
    check(http, BY_DATE, 400, get(BY_DATE));

    // A 500 cannot be caused from outside; HttpErrorResponsesIT checks its problem shape
    assertThat(covered)
        .containsAll(
            ReadOpenApi.documentedResponses().stream()
                .filter(response -> !response.endsWith(" 500"))
                .toList());
    String all = String.join("\n", bodies);
    assertThat(all).contains(ISIN, "BSE_fgroup01012026.csv");
    // I-READ-08: decimals keep their scale and every timestamp is UTC
    assertThat(all).contains("\"openPrice\":114200.00", "\"value\":8.94");
    assertThat(all).doesNotContainPattern("T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?[+-]\\d{2}:\\d{2}");
    for (String location :
        List.of(
            BUCKET, SOURCE_URL, VERSION, CANONICAL_KEY, CSV_KEY, JSON_KEY, "runs/run_", "s3:")) {
      assertThat(all).as("no response shows %s", location).doesNotContain(location);
    }
  }

  private void check(MockMvc http, String path, int status, MockHttpServletRequestBuilder request)
      throws Exception {
    MockHttpServletResponse response = http.perform(request).andReturn().getResponse();
    String body = response.getContentAsString();
    assertThat(response.getStatus()).as("%s: %s", path, body).isEqualTo(status);
    String contentType = Objects.requireNonNull(response.getContentType());
    assertThat(ReadOpenApi.violations(path, status, contentType, body))
        .as("%s %s: %s", path, status, body)
        .isEmpty();
    covered.add(path + " " + status);
    bodies.add(body);
  }

  private JobId submitted(Map<String, Object> event) {
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    jdbc.sql(
            "UPDATE data_processing.ingestion_requests SET manifest_version_id = :version"
                + " WHERE id = :id")
        .param("version", VERSION)
        .param("id", job.value())
        .update();
    return job;
  }

  private UUID start(JobId job) {
    UUID run = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          assertThat(jobRuns.lockForRun(job)).isPresent();
          jobRuns.startRun(job, run, 1, NOW);
        });
    return run;
  }

  /**
   * Lists one CSV file with its bucket, key, version and source URL. The source URL is not stored
   * anywhere today, so for it this test cannot fail; U-REV-06 guards against a field that would
   * carry it.
   */
  private void listCsv(JobId job) {
    sourceFiles.saveAll(
        job,
        List.of(
            new ManifestFile(
                "debt-bhavcopy",
                BUCKET,
                CSV_KEY,
                SourceFormat.CSV,
                "a".repeat(64),
                10,
                SOURCE_URL)));
    jdbc.sql("UPDATE data_processing.source_files SET version_id = :version")
        .param("version", VERSION)
        .update();
  }

  private JobId completedCsvJob() {
    JobId job = submitted(SubmissionEvents.bse("run_c1", "2026-01-01"));
    listCsv(job);
    UUID run = start(job);
    jobRuns.recordCanonicalFile(run, CANONICAL_KEY);
    // One rejected row, stored under the source key the way the pipeline stores it
    csvRejections.saveAll(
        new CanonicalRun(
            job,
            1,
            GoldenBhavcopy.RUN.tradeDate(),
            BUCKET,
            CSV_KEY,
            GoldenBhavcopy.RUN.sourceContractVersion(),
            GoldenBhavcopy.RUN.mappingContractVersion()),
        run,
        List.of(RejectedRow.of(GoldenBhavcopy.rows().get(5), "isin", 1)));
    jobRuns.completeRun(
        job, run, 1, new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, CSV_COUNTS, 1), NOW);
    return job;
  }

  /**
   * The failure detail is echoed verbatim. The service builds it from file basenames and S3 error
   * codes, never from a raw exception message; keeping keys out of it is the job of the code that
   * writes it (S3SourceStore, LoadManifest), not of this test.
   */
  private JobId failedCsvJob() {
    JobId job = submitted(SubmissionEvents.bse("run_c3", "2026-01-03"));
    listCsv(job);
    UUID run = start(job);
    jobRuns.failRun(
        job,
        run,
        1,
        RunStatus.FAILED_PERMANENT,
        "CHECKSUM_MISMATCH",
        "The file's sha256 differs from the manifest.",
        JobStatus.FAILED,
        NOW);
    return job;
  }

  private JobId jsonJobWithErrors() {
    JobId job = submitted(SubmissionEvents.nsdl("run_201", ISIN));
    UUID run = start(job);
    long stored =
        rejections.saveJson(
            new JsonCanonicalRun(
                job, 1, Isin.of(ISIN), "nsdl-security-json-v1", "nsdl-security-mapping-v1"),
            run,
            List.of(
                JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED)),
                JsonEvidence.skipped()));
    jobRuns.completeRun(
        job,
        run,
        1,
        new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, JSON_COUNTS, Math.toIntExact(stored)),
        NOW);
    return job;
  }

  /** A full security, an ISIN-only one, and summaries, each row naming its source object. */
  private void security() {
    jdbc.sql(
            """
            INSERT INTO securities_data.securities
              (isin, issuer_name, issuer_ownership_type, instrument_type, allotment_date,
               redemption_date, original_face_value, collateral_status, asset_coverage_basis,
               asset_coverage_value, asset_coverage_unit, coupon_rate_value, coupon_rate_unit,
               coupon_type, listing_status, field_sources, created_at, updated_at)
            VALUES (:isin, 'ADITYA BIRLA HOUSING FINANCE LIMITED', 'Non PSU', 'Debentures',
               DATE '2019-06-10', DATE '2029-06-08', 1000000, 'Secured', 'Book Debts', 100.0,
               'PERCENT', 8.94, 'PERCENT', 'Simple', 'Listed', CAST(:sources AS JSONB),
               TIMESTAMPTZ '2026-09-21T15:00:00Z', TIMESTAMPTZ '2026-09-27T14:31:02Z')
            """)
        .param("isin", ISIN)
        .param(
            "sources",
            "{\"couponType\": {\"bucket\": \"" + BUCKET + "\", \"key\": \"" + JSON_KEY + "\"}}")
        .update();
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES ('INE000000001', now(), now())")
        .update();
    collection(
        "security_ratings",
        "source_category, rating_agency_name, rating",
        "'CURRENT', 'ICRA', 'AAA'");
    collection(
        "security_ratings",
        "source_category, rating_agency_name, rating",
        "'EARLIER', 'CARE', 'A'");
    collection("security_listings", "exchange_name, listing_date", "'NSE', DATE '2019-06-14'");
    collection("security_cash_flows", "event_type, due_date", "'Interest', DATE '2027-06-08'");
    collection("security_collateral_assets", "asset_type", "'Receivables'");
    for (String exchange : List.of("BSE", "NSE")) {
      jdbc.sql(
              """
              INSERT INTO securities_data.security_daily_market_summaries
                (isin, trade_date, exchange_name, security_code, open_price, close_price,
                 traded_volume, number_of_trades, turnover, face_value, source_request_id,
                 source_file, source_location, created_at, updated_at)
              VALUES (:isin, DATE '2026-01-01', :exchange, '976009', 114200.00, 114200.00, 14,
                 1, 1598800.00, 100000.00, :request, :file, 'row 2',
                 TIMESTAMPTZ '2026-01-01T15:00:00Z', TIMESTAMPTZ '2026-01-01T15:00:00Z')
              """)
          .param("isin", ISIN)
          .param("exchange", exchange)
          .param("request", UUID.randomUUID())
          .param("file", CSV_KEY)
          .update();
    }
  }

  /** Inserts one collection row whose source reference is an object key. */
  private void collection(String table, String columns, String values) {
    jdbc.sql(
            "INSERT INTO securities_data."
                + table
                + " (id, isin, "
                + columns
                + ", source_request_id, source_file, source_location, first_recorded_at)"
                + " VALUES (:id, :isin, "
                + values
                + ", :request, :file, '$.x', now())")
        .param("id", UUID.randomUUID())
        .param("isin", ISIN)
        .param("request", UUID.randomUUID())
        .param("file", JSON_KEY + "/INE831R08076_instrument-details.json")
        .update();
  }
}
