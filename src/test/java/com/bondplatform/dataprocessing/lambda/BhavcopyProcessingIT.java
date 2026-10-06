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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A bhavcopy from submission to terminal status with the real BSE handler (I-CSV-01, I-CSV-02 end
 * to end, I-CSV-03, I-EVT-01 delivery, I-EVT-04): PostgreSQL, an S3-compatible server holding the
 * manifest, the source file, and the canonical files, and an SQS-compatible server for the job and
 * security-details queues. The job queue is polled here as Lambda's event source mapping would.
 */
@ExtendWith(OutputCaptureExtension.class)
class BhavcopyProcessingIT extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SOURCE_BUCKET = "data-fetch-service-artifacts";
  private static final String CANONICAL_BUCKET = "data-processing-service-canonical";
  private static final String HEADER =
      "Security_cd,ISIN No.,Open Price,High Price,Low Price,Close Price,"
          + "Total Traded Volume,Number of Trades,Total Turnover,FACE VALUE";
  private static final S3Client S3 = S3Mock.client();
  private static final SqsClient SQS = ElasticMq.client();
  private static final String JOBS =
      SQS.createQueue(
              request ->
                  request
                      .queueName("bhavcopy-jobs.fifo")
                      .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
          .queueUrl();
  private static final String DETAILS =
      SQS.createQueue(request -> request.queueName("bhavcopy-details")).queueUrl();

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

  // I-CSV-01, I-EVT-01, I-OPS-05 for the worker function
  @Test
  void cleanBhavcopyCompletesStoresItsCanonicalFileAndRequestsNewSecuritiesDetails(
      CapturedOutput output) {
    JobId job =
        process(
            "run_e2e_1",
            "2026-01-05",
            csv(
                "973812,INE001A07AB1,100,101,99,100.5,10,2,1000,1000",
                "973813,INE002B08CD2,99,99,99,99,1,1,99,100"));

    assertThat(statusOf(job)).isEqualTo("COMPLETED");
    assertThat(countsOf(job))
        .isEqualTo(
            "{\"invalidRows\": 0, \"acceptedRows\": 2, \"sourceRecords\": 2,"
                + " \"supersededRows\": 0}");
    assertThat(
            jdbc.sql(
                    "SELECT face_value FROM securities_data.security_daily_market_summaries"
                        + " WHERE isin = 'INE001A07AB1' AND trade_date = DATE '2026-01-05'")
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("1000");
    assertThat(isins()).containsExactly("INE001A07AB1", "INE002B08CD2");
    String canonicalKey = canonicalKeyOf(job);
    assertThat(canonicalKey).isEqualTo("canonical/" + job.value() + "/attempt-1/canonical.jsonl");
    assertThat(S3.getObjectAsBytes(request -> request.bucket(CANONICAL_BUCKET).key(canonicalKey)))
        .satisfies(file -> assertThat(file.asUtf8String().lines()).hasSize(2));
    List<Message> requests = detailRequests(2);
    assertThat(requests)
        .allSatisfy(
            message ->
                assertThat(message.messageAttributes())
                    .extractingByKey("contentType")
                    .extracting(MessageAttributeValue::stringValue)
                    .isEqualTo("application/cloudevents+json"));
    assertThat(requests)
        .extracting(message -> subjectOf(message.body()))
        .containsExactlyInAnyOrder("isin/INE001A07AB1", "isin/INE002B08CD2");
    JsonNode ended = JsonLines.log(output, "Attempt ended");
    assertThat(ended.path("jobId").asString()).isEqualTo(job.value().toString());
    assertThat(ended.path("attemptNumber").asString()).isEqualTo("1");
    assertThat(JsonLines.metric(output, "JobOutcome"))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.path("Outcome").asString()).isEqualTo("COMPLETED");
              assertThat(line.path("Dataset").asString())
                  .isEqualTo("urn:bond-platform:dataset:bse-debt-trades");
              assertThat(line.path("JobOutcome").asLong()).isOne();
            });
    assertThat(JsonLines.metric(output, "RunDuration")).hasSize(1);
  }

  // I-CSV-02 end to end, I-EVT-04
  @Test
  void goldenBhavcopyCompletesWithErrorsAndRejectedRowsCreateNoSecurity() {
    JobId job = process("run_e2e_2", "2026-01-01", golden(), "BSE_fgroup01012026.csv");

    assertThat(statusOf(job)).isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(countsOf(job))
        .isEqualTo(
            "{\"invalidRows\": 4, \"acceptedRows\": 3, \"sourceRecords\": 8,"
                + " \"supersededRows\": 1}");
    assertThat(
            jdbc.sql("SELECT count(*) FROM data_processing.rejected_records")
                .query(Long.class)
                .single())
        .isEqualTo(5);
    assertThat(isins()).containsExactly("INE001A07AB1", "INE002B08CD2", "INE004D07GH4");
    assertThat(detailRequests(3))
        .extracting(message -> subjectOf(message.body()))
        .doesNotContain("isin/INE003C07EF3");
  }

  // I-CSV-03
  @Test
  void malformedLateRecordFailsTheJobAndChangesNoSummary() {
    process("run_e2e_3", "2026-01-06", csv("973812,INE001A07AB1,100,101,99,100.5,10,2,1000,1000"));
    detailRequests(1);

    JobId failed =
        process(
            "run_e2e_4",
            "2026-01-06",
            csv(
                "973812,INE001A07AB1,200,201,199,200.5,10,2,1000,1000",
                "973813,INE002B08CD2,99,99,99,99,1,1,99,100",
                "973814,INE003C07EF3,99,99"));

    assertThat(statusOf(failed)).isEqualTo("FAILED");
    assertThat(
            jdbc.sql(
                    "SELECT failure_code FROM data_processing.processing_runs"
                        + " WHERE ingestion_request_id = :id")
                .param("id", failed.value())
                .query(String.class)
                .single())
        .isEqualTo("MALFORMED_CSV");
    assertThat(
            jdbc.sql(
                    "SELECT close_price FROM securities_data.security_daily_market_summaries"
                        + " WHERE trade_date = DATE '2026-01-06'")
                .query(BigDecimal.class)
                .list())
        .singleElement()
        .satisfies(price -> assertThat(price).isEqualByComparingTo("100.5"));
    assertThat(isins()).containsExactly("INE001A07AB1");
    assertThat(
            SQS.receiveMessage(request -> request.queueUrl(DETAILS).waitTimeSeconds(1)).messages())
        .isEmpty();
  }

  private JobId process(String runId, String tradeDate, byte[] content) {
    String day = tradeDate.substring(8, 10) + tradeDate.substring(5, 7) + tradeDate.substring(0, 4);
    return process(runId, tradeDate, content, "BSE_fgroup" + day + ".csv");
  }

  /** Stores the file and its manifest, submits the run, and runs the worker until it ends. */
  private JobId process(String runId, String tradeDate, byte[] content, String fileName) {
    Map<String, Object> submission = SubmissionEvents.bse(runId, tradeDate);
    String key = "runs/" + runId + "/raw/debt-bhavcopy/1/" + fileName;
    put(key, content);
    put("runs/" + runId + "/manifest.json", manifestOf(submission, key, content));
    JobId job =
        admitSubmission.admit(SubmissionEvents.submissionOf(submission), submission).jobId();
    Set<String> terminal = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED");
    pump.until(() -> terminal.contains(statusOf(job)));
    return job;
  }

  /** Returns the manifest the fetch service would write for the submission, listing one CSV. */
  private static byte[] manifestOf(Map<String, Object> submission, String key, byte[] content) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) submission.get("data"));
    data.remove("manifest");
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("job_id", "debt-bhavcopy");
    file.put("bucket", SOURCE_BUCKET);
    file.put("key", key);
    file.put("format", "csv");
    file.put("sha256", sha256(content));
    file.put("size_bytes", content.length);
    data.put("files", List.of(file));
    Map<String, Object> manifest = new LinkedHashMap<>(submission);
    manifest.put("data", data);
    return JSON.writeValueAsBytes(manifest);
  }

  private List<Message> detailRequests(int expected) {
    List<Message> received = new ArrayList<>();
    for (int poll = 0; poll < 10 && received.size() < expected; poll++) {
      received.addAll(
          SQS.receiveMessage(
                  request ->
                      request
                          .queueUrl(DETAILS)
                          .maxNumberOfMessages(10)
                          .waitTimeSeconds(1)
                          .messageAttributeNames("All"))
              .messages());
    }
    assertThat(received).hasSize(expected);
    received.forEach(
        message ->
            SQS.deleteMessage(
                request -> request.queueUrl(DETAILS).receiptHandle(message.receiptHandle())));
    return received;
  }

  private String statusOf(JobId job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
  }

  private String countsOf(JobId job) {
    return jdbc.sql("SELECT counts::text FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
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

  private List<String> isins() {
    return jdbc.sql("SELECT isin FROM securities_data.securities ORDER BY isin")
        .query(String.class)
        .list();
  }

  private static String subjectOf(String cloudEvent) {
    return JSON.readTree(cloudEvent).get("subject").asString();
  }

  private static byte[] csv(String... rows) {
    return (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  static byte[] golden() {
    try (InputStream in =
        BhavcopyProcessingIT.class.getResourceAsStream("/fixtures/bse/BSE_fgroup01012026.csv")) {
      if (in == null) {
        throw new IllegalStateException("Missing golden bhavcopy");
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
