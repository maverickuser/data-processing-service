package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.job.application.RunJob;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.adapter.aws.S3Mock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * NSDL security-details requests from submission to terminal status with the real NSDL handler
 * (I-JSON-01, 02, 03, 04, 06, 07 and 08 end to end): PostgreSQL, an S3-compatible server holding
 * the manifests, the JSON files and the canonical files, and an SQS-compatible job queue polled
 * here as Lambda's event source mapping would.
 */
class NsdlProcessingIT extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ISIN = "INE831R08076";
  private static final String SOURCE_BUCKET = "data-fetch-service-artifacts";
  private static final String CANONICAL_BUCKET = "data-processing-service-canonical";
  private static final List<String> SAMPLES =
      List.of(
          "isin-details",
          "instrument-details",
          "coupon-details",
          "credit-ratings",
          "listings",
          "redemptions");
  private static final S3Client S3 = S3Mock.client();
  private static final SqsClient SQS = ElasticMq.client();
  private static final String JOBS =
      SQS.createQueue(
              request ->
                  request
                      .queueName("nsdl-jobs.fifo")
                      .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
          .queueUrl();
  private static final String DETAILS =
      SQS.createQueue(request -> request.queueName("nsdl-details")).queueUrl();

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private RunJob runJob;

  private WorkerPump pump;

  @DynamicPropertySource
  static void useTestStorageAndQueues(DynamicPropertyRegistry registry) {
    registry.add("data-processing.sources.endpoint", () -> S3Mock.endpoint().toString());
    registry.add("data-processing.queues.endpoint", () -> ElasticMq.endpoint().toString());
    registry.add("data-processing.queues.file-processing-url", () -> JOBS);
    registry.add("data-processing.queues.security-details-url", () -> DETAILS);
  }

  @BeforeEach
  void emptyQueuesAndCreateBuckets() {
    SQS.purgeQueue(request -> request.queueUrl(JOBS));
    SQS.purgeQueue(request -> request.queueUrl(DETAILS));
    S3Mock.createBucket(S3, SOURCE_BUCKET);
    S3Mock.createBucket(S3, CANONICAL_BUCKET);
    pump = new WorkerPump(SQS, JOBS, runJob);
  }

  // I-JSON-01
  @Test
  void goldenManifestEnrichesTheIsinOnlySecurityAndStoresCollections() {
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (:isin, now(), now())")
        .param("isin", ISIN)
        .update();

    JobId job = process("run_n1", ISIN, samples());

    assertThat(statusOf(job)).isEqualTo("COMPLETED");
    JsonNode counts = countsOf(job);
    assertThat(counts.get("filesListed").asInt()).isEqualTo(6);
    assertThat(counts.get("filesProcessed").asInt()).isEqualTo(6);
    assertThat(counts.get("fieldsRejected").asInt()).isZero();
    assertThat(counts.get("scalarFieldsChanged").asInt()).isPositive();
    assertThat(counts.get("collectionEntriesAppended").asInt()).isPositive();
    Map<String, Object> security = security();
    assertThat(security.get("issuer_name")).isEqualTo("ADITYA BIRLA HOUSING FINANCE LIMITED");
    assertThat(security.get("coupon_type")).isEqualTo("Simple");
    assertThat(count("security_listings")).isPositive();
    assertThat(count("security_ratings")).isPositive();
    assertThat(errorCount(job)).isZero();
    String key = canonicalKeyOf(job);
    assertThat(
            S3.getObjectAsBytes(request -> request.bucket(CANONICAL_BUCKET).key(key))
                .asUtf8String()
                .lines())
        .hasSize(6);
  }

  // I-JSON-02 end to end
  @Test
  void resubmittingTheSameFilesChangesNothing() {
    process("run_n1", ISIN, samples());
    Map<String, Object> before = security();
    long listings = count("security_listings");
    long ratings = count("security_ratings");

    JobId second = process("run_n2", ISIN, samples());

    assertThat(statusOf(second)).isEqualTo("COMPLETED");
    JsonNode counts = countsOf(second);
    assertThat(counts.get("scalarFieldsChanged").asInt()).isZero();
    assertThat(counts.get("collectionEntriesAppended").asInt()).isZero();
    assertThat(security()).isEqualTo(before);
    assertThat(count("security_listings")).isEqualTo(listings);
    assertThat(count("security_ratings")).isEqualTo(ratings);
  }

  // I-JSON-03 end to end
  @Test
  void laterSubmissionUpdatesOnlyTheChangedField() {
    process("run_n1", ISIN, samples());
    Map<String, Object> before = security();

    JobId second = process("run_n2", ISIN, Map.of("coupon-details", coupon("Compound")));

    assertThat(statusOf(second)).isEqualTo("COMPLETED");
    assertThat(countsOf(second).get("scalarFieldsChanged").asInt()).isEqualTo(1);
    Map<String, Object> after = security();
    assertThat(after.get("coupon_type")).isEqualTo("Compound");
    assertThat(after.get("issuer_name")).isEqualTo(before.get("issuer_name"));
    assertThat(source("couponType")).isEqualTo(second.value().toString());
    assertThat(updatedAt(after)).isAfter(updatedAt(before));
  }

  // I-JSON-04 end to end
  @Test
  void malformedFileAndChecksumMismatchAreSkippedAndTheRestApplied() {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("isin-details", sample("isin-details"));
    files.put("coupon-details", bytes("{\"coupensVo\": "));
    files.put("listings", sample("listings"));

    JobId job = process("run_n1", ISIN, files, Set.of("listings"));

    assertThat(statusOf(job)).isEqualTo("COMPLETED_WITH_ERRORS");
    JsonNode counts = countsOf(job);
    assertThat(counts.get("filesProcessed").asInt()).isEqualTo(1);
    assertThat(counts.get("filesSkipped").asInt()).isEqualTo(2);
    assertThat(security().get("issuer_name")).isEqualTo("ADITYA BIRLA HOUSING FINANCE LIMITED");
    assertThat(count("security_listings")).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT code FROM data_processing.validation_issues i"
                        + " JOIN data_processing.processing_runs r ON r.id = i.processing_run_id"
                        + " WHERE r.ingestion_request_id = :id ORDER BY i.sequence_number")
                .param("id", job.value())
                .query(String.class)
                .list())
        .containsExactly("MALFORMED_JSON", "CHECKSUM_MISMATCH");
    assertThat(errorCount(job)).isEqualTo(2);
  }

  // I-JSON-06: requests for one ISIN apply in acceptance order; other ISINs are independent
  @Test
  void requestsForOneIsinApplyInAcceptanceOrder() {
    JobId first = submit("run_n1", ISIN, Map.of("coupon-details", coupon("Simple")), Set.of());
    JobId second = submit("run_n2", ISIN, Map.of("coupon-details", coupon("Compound")), Set.of());
    JobId other =
        submit(
            "run_n3",
            "INE0O7U07046",
            Map.of("coupon-details", sample("INE0O7U07046", "coupon-details")),
            Set.of());

    Set<String> terminal = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED");
    pump.until(
        () ->
            List.of(first, second, other).stream()
                .allMatch(job -> terminal.contains(statusOf(job))));

    assertThat(statusOf(first)).isEqualTo("COMPLETED");
    assertThat(statusOf(second)).isEqualTo("COMPLETED");
    assertThat(statusOf(other)).isEqualTo("COMPLETED");
    assertThat(security().get("coupon_type")).isEqualTo("Compound");
    assertThat(source("couponType")).isEqualTo(second.value().toString());
  }

  // I-JSON-07 end to end
  @Test
  void unsecuredAfterSecuredClearsCoverageAndKeepsAssetRows() {
    process(
        "run_n1",
        ISIN,
        Map.of(
            "instrument-details",
            bytes(
                """
                {"instrumentsVo": {"assetCover": {"securedFlag": "Secured",
                  "assetCvge": "Book Debts", "assetCvrPercent": "100%",
                  "assetList": [{"assetType": "Receivables"}]}}}
                """)));
    assertThat(security().get("asset_coverage_value")).isNotNull();

    JobId second =
        process(
            "run_n2",
            ISIN,
            Map.of(
                "instrument-details",
                bytes("{\"instrumentsVo\": {\"assetCover\": {\"securedFlag\": \"UNSECURED\"}}}")));

    assertThat(statusOf(second)).isEqualTo("COMPLETED");
    Map<String, Object> security = security();
    assertThat(security.get("collateral_status")).isEqualTo("UNSECURED");
    assertThat(security.get("asset_coverage_value")).isNull();
    assertThat(security.get("asset_coverage_basis")).isNull();
    assertThat(count("security_collateral_assets")).isEqualTo(1);
  }

  // I-JSON-08
  @Test
  void securityDetailsLeaveDailySummariesUntouched() {
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (:isin, now(), now())")
        .param("isin", ISIN)
        .update();
    jdbc.sql(
            """
            INSERT INTO securities_data.security_daily_market_summaries
              (isin, trade_date, exchange_name, close_price, source_request_id, source_file,
               source_location, created_at, updated_at)
            VALUES (:isin, DATE '2026-01-05', 'BSE', 100.5, gen_random_uuid(),
               'BSE_fgroup05012026.csv', '2', now(), now())
            """)
        .param("isin", ISIN)
        .update();
    Map<String, Object> before = summary();

    process("run_n1", ISIN, samples());

    assertThat(summary()).isEqualTo(before);
  }

  private JobId process(String runId, String isin, Map<String, byte[]> files) {
    return process(runId, isin, files, Set.of());
  }

  /** Stores the files and their manifest, submits the run, and runs the worker until it ends. */
  private JobId process(
      String runId, String isin, Map<String, byte[]> files, Set<String> wrongChecksum) {
    JobId job = submit(runId, isin, files, wrongChecksum);
    Set<String> terminal = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED");
    pump.until(() -> terminal.contains(statusOf(job)));
    return job;
  }

  private JobId submit(
      String runId, String isin, Map<String, byte[]> files, Set<String> wrongChecksum) {
    Map<String, Object> submission = SubmissionEvents.nsdl(runId, isin);
    List<Map<String, Object>> listed = new ArrayList<>();
    files.forEach(
        (suffix, content) -> {
          String key = "runs/" + runId + "/raw/" + suffix + "/1/" + isin + "_" + suffix + ".json";
          put(key, content);
          Map<String, Object> file = new LinkedHashMap<>();
          file.put("job_id", suffix);
          file.put("bucket", SOURCE_BUCKET);
          file.put("key", key);
          file.put("format", "json");
          file.put("sha256", wrongChecksum.contains(suffix) ? "0".repeat(64) : sha256(content));
          file.put("size_bytes", content.length);
          listed.add(file);
        });
    put("runs/" + runId + "/manifest.json", manifestOf(submission, listed));
    return admitSubmission.admit(SubmissionEvents.submissionOf(submission), submission).jobId();
  }

  /** Returns the manifest the fetch service would write for the submission. */
  private static byte[] manifestOf(
      Map<String, Object> submission, List<Map<String, Object>> files) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) submission.get("data"));
    data.remove("manifest");
    data.put("files", files);
    Map<String, Object> manifest = new LinkedHashMap<>(submission);
    manifest.put("data", data);
    return JSON.writeValueAsBytes(manifest);
  }

  private String statusOf(JobId job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
  }

  private JsonNode countsOf(JobId job) {
    return JSON.readTree(
        jdbc.sql("SELECT counts::text FROM data_processing.ingestion_requests WHERE id = :id")
            .param("id", job.value())
            .query(String.class)
            .single());
  }

  private long errorCount(JobId job) {
    return jdbc.sql("SELECT error_count FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(Long.class)
        .single();
  }

  private String canonicalKeyOf(JobId job) {
    return jdbc.sql(
            "SELECT canonical_object_key FROM data_processing.processing_runs"
                + " WHERE ingestion_request_id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
  }

  private Map<String, Object> security() {
    return jdbc.sql(
            """
            SELECT issuer_name, coupon_type, collateral_status, asset_coverage_basis,
                   asset_coverage_value, field_sources::text AS field_sources, updated_at
            FROM securities_data.securities WHERE isin = :isin
            """)
        .param("isin", ISIN)
        .query()
        .singleRow();
  }

  private Map<String, Object> summary() {
    return jdbc.sql(
            "SELECT * FROM securities_data.security_daily_market_summaries WHERE isin = :isin")
        .param("isin", ISIN)
        .query()
        .singleRow();
  }

  private String source(String field) {
    return jdbc.sql(
            "SELECT field_sources -> :field ->> 'sourceRequestId'"
                + " FROM securities_data.securities WHERE isin = :isin")
        .param("field", field)
        .param("isin", ISIN)
        .query(String.class)
        .single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM securities_data." + table).query(Long.class).single();
  }

  private static Instant updatedAt(Map<String, Object> security) {
    return ((Timestamp) Objects.requireNonNull(security.get("updated_at"))).toInstant();
  }

  private static Map<String, byte[]> samples() {
    Map<String, byte[]> files = new LinkedHashMap<>();
    SAMPLES.forEach(suffix -> files.put(suffix, sample(suffix)));
    return files;
  }

  private static byte[] coupon(String type) {
    return bytes("{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"" + type + "\"}}}");
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] sample(String suffix) {
    return sample(ISIN, suffix);
  }

  private static byte[] sample(String isin, String suffix) {
    String name = "/fixtures/nsdl/" + isin + "_" + suffix + ".json";
    try (InputStream in = NsdlProcessingIT.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing sample " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void put(String key, byte[] content) {
    S3.putObject(request -> request.bucket(SOURCE_BUCKET).key(key), RequestBody.fromBytes(content));
  }
}
