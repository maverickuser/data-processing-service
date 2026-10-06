package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.job.application.RunJob;
import com.bondplatform.dataprocessing.operations.application.OutboxBacklogMonitor;
import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.review.application.ListDailyMarketSummaries;
import com.bondplatform.dataprocessing.review.application.ListJobErrors;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.adapter.aws.S3Mock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Least privilege for the database (LLD section 23.5): each function does its real work logged in
 * as its own role from migration V5, and no role holds a privilege its function does not use. Each
 * function runs in an application context of its own, as in Lambda.
 */
class FunctionRolesIT extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "function-role";
  private static final String SOURCE_BUCKET = "data-fetch-service-artifacts";
  private static final String CANONICAL_BUCKET = "data-processing-service-canonical";
  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED");
  private static final S3Client S3 = S3Mock.client();
  private static final SqsClient SQS = ElasticMq.client();
  private static final String JOBS =
      SQS.createQueue(
              request ->
                  request
                      .queueName("roles-jobs.fifo")
                      .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
          .queueUrl();
  private static final String DETAILS =
      SQS.createQueue(request -> request.queueName("roles-details")).queueUrl();

  /** Every privilege a function role holds on a table; anything more fails the test. */
  private static final String GRANTS =
      """
      processing_reader data_processing.ingestion_requests SELECT
      processing_reader data_processing.processing_runs SELECT
      processing_reader data_processing.source_files SELECT
      processing_reader data_processing.validation_issues SELECT
      processing_reader securities_data.securities SELECT
      processing_reader securities_data.security_cash_flows SELECT
      processing_reader securities_data.security_collateral_assets SELECT
      processing_reader securities_data.security_daily_market_summaries SELECT
      processing_reader securities_data.security_listings SELECT
      processing_reader securities_data.security_ratings SELECT
      processing_retention data_processing.outbox_events DELETE
      processing_retention data_processing.outbox_events SELECT
      processing_retention data_processing.rejected_records DELETE
      processing_retention data_processing.rejected_records SELECT
      processing_retention data_processing.validation_issues DELETE
      processing_retention data_processing.validation_issues SELECT
      processing_submission data_processing.ingestion_requests INSERT
      processing_submission data_processing.ingestion_requests SELECT
      processing_submission data_processing.outbox_events INSERT
      processing_submission data_processing.outbox_events SELECT
      processing_submission data_processing.outbox_events UPDATE
      processing_sweeper data_processing.ingestion_requests SELECT
      processing_sweeper data_processing.ingestion_requests UPDATE
      processing_sweeper data_processing.outbox_events SELECT
      processing_sweeper data_processing.outbox_events UPDATE
      processing_sweeper data_processing.processing_runs SELECT
      processing_sweeper data_processing.processing_runs UPDATE
      processing_worker data_processing.ingestion_requests SELECT
      processing_worker data_processing.ingestion_requests UPDATE
      processing_worker data_processing.outbox_events INSERT
      processing_worker data_processing.outbox_events SELECT
      processing_worker data_processing.outbox_events UPDATE
      processing_worker data_processing.processing_runs INSERT
      processing_worker data_processing.processing_runs SELECT
      processing_worker data_processing.processing_runs UPDATE
      processing_worker data_processing.rejected_records INSERT
      processing_worker data_processing.source_files INSERT
      processing_worker data_processing.source_files SELECT
      processing_worker data_processing.validation_issues INSERT
      processing_worker securities_data.securities INSERT
      processing_worker securities_data.securities SELECT
      processing_worker securities_data.securities UPDATE
      processing_worker securities_data.security_cash_flows INSERT
      processing_worker securities_data.security_cash_flows SELECT
      processing_worker securities_data.security_collateral_assets INSERT
      processing_worker securities_data.security_collateral_assets SELECT
      processing_worker securities_data.security_daily_market_summaries INSERT
      processing_worker securities_data.security_daily_market_summaries SELECT
      processing_worker securities_data.security_daily_market_summaries UPDATE
      processing_worker securities_data.security_listings INSERT
      processing_worker securities_data.security_listings SELECT
      processing_worker securities_data.security_ratings INSERT
      processing_worker securities_data.security_ratings SELECT
      """;

  @Value("${spring.datasource.url}")
  private String databaseUrl;

  private final List<ConfigurableApplicationContext> functions = new ArrayList<>();

  @BeforeEach
  void prepareRolesQueuesAndBuckets() {
    for (String role : FunctionStartupIT.FUNCTION_ROLES) {
      jdbc.sql("ALTER ROLE " + role + " PASSWORD '" + PASSWORD + "'").update();
    }
    SQS.purgeQueue(request -> request.queueUrl(JOBS));
    SQS.purgeQueue(request -> request.queueUrl(DETAILS));
    S3Mock.createBucket(S3, SOURCE_BUCKET);
    S3Mock.createBucket(S3, CANONICAL_BUCKET);
  }

  @AfterEach
  void stopFunctions() {
    functions.forEach(ConfigurableApplicationContext::close);
  }

  @Test
  void eachFunctionDoesItsWorkLoggedInAsItsOwnRole() {
    AdmitSubmission submission = function("processing_submission").getBean(AdmitSubmission.class);
    JobId bhavcopy = submitBhavcopy(submission);
    JobId nsdl = submitNsdl(submission);

    RunJob worker = function("processing_worker").getBean(RunJob.class);
    new WorkerPump(SQS, JOBS, worker)
        .until(() -> TERMINAL.contains(statusOf(bhavcopy)) && TERMINAL.contains(statusOf(nsdl)));
    assertThat(statusOf(bhavcopy))
        .as("rows were published and the invalid ones rejected")
        .isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(statusOf(nsdl)).isIn("COMPLETED", "COMPLETED_WITH_ERRORS");

    // A job admitted but never run, an hour past its submission, with its dispatch still due
    JobId stuck = submit(submission, SubmissionEvents.bse("run_roles_3", "2026-01-02"), List.of());
    SQS.purgeQueue(request -> request.queueUrl(JOBS));
    assertThat(
            jdbc.sql("SELECT DISTINCT status FROM data_processing.outbox_events")
                .query(String.class)
                .list())
        .as("submission and worker delivered every event they wrote")
        .containsExactly("DELIVERED");
    jdbc.sql(
            "UPDATE data_processing.ingestion_requests"
                + " SET submitted_at = now() - INTERVAL '2 hours' WHERE id = :id")
        .param("id", stuck.value())
        .update();
    jdbc.sql(
            "UPDATE data_processing.outbox_events SET status = 'PENDING', delivered_at = NULL,"
                + " next_attempt_at = now() - INTERVAL '1 hour' WHERE payload ->> 'jobId' = :id")
        .param("id", stuck.value().toString())
        .update();
    ConfigurableApplicationContext sweeper = function("processing_sweeper");
    assertThat(sweeper.getBean(StuckJobFailer.class).failStuckJobs()).isEqualTo(1);
    assertThat(sweeper.getBean(OutboxDispatcher.class).sweep()).isEqualTo(1);
    assertThat(sweeper.getBean(OutboxBacklogMonitor.class).oldestPendingAge())
        .isEqualTo(Duration.ZERO);

    assertThat(function("processing_retention").getBean(RetentionCleaner.class).clean())
        .isNotNull();

    ConfigurableApplicationContext reader = function("processing_reader");
    assertThat(reader.getBean(GetSecurity.class).find(Isin.of(NsdlProcessingIT.ISIN))).isPresent();
    assertThat(
            reader
                .getBean(ListDailyMarketSummaries.class)
                .forTradeDate(LocalDate.parse("2026-01-01"), null, null)
                .items())
        .isNotEmpty();
    assertThat(
            reader
                .getBean(ListDailyMarketSummaries.class)
                .forSecurity(Isin.of("INE121A07QY9"), null, null, null))
        .as("an unknown ISIN, checked against the securities")
        .isEmpty();
    assertThat(reader.getBean(GetJobStatus.class).find(bhavcopy)).isPresent();
    assertThat(reader.getBean(ListJobErrors.class).list(bhavcopy, null, null)).isPresent();
  }

  @Test
  void noRoleHoldsAPrivilegeItsFunctionDoesNotUse() {
    List<String> held =
        jdbc.sql(
                """
                SELECT grantee || ' ' || table_schema || '.' || table_name || ' ' || privilege_type
                FROM information_schema.role_table_grants
                WHERE grantee LIKE 'processing\\_%'
                ORDER BY 1
                """)
            .query(String.class)
            .list();

    assertThat(held)
        .containsExactlyInAnyOrderElementsOf(GRANTS.lines().map(String::strip).toList());
  }

  @Test
  void readerSessionsAreReadOnlyAndCutOffLongStatements() {
    JdbcClient asReader =
        JdbcClient.create(new DriverManagerDataSource(databaseUrl, "processing_reader", PASSWORD));

    assertThat(asReader.sql("SHOW default_transaction_read_only").query(String.class).single())
        .isEqualTo("on");
    assertThat(asReader.sql("SHOW statement_timeout").query(String.class).single())
        .isEqualTo("10s");
    assertThatThrownBy(() -> asReader.sql("CREATE TEMPORARY TABLE scratch (id INT)").update())
        .rootCause()
        .hasMessageContaining("read-only transaction");
  }

  /** Starts a function's application context, logged in as the given role. */
  private ConfigurableApplicationContext function(String role) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(DataProcessingApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + databaseUrl,
                "--spring.datasource.username=" + role,
                "--spring.datasource.password=" + PASSWORD,
                "--data-processing.sources.endpoint=" + S3Mock.endpoint(),
                "--data-processing.queues.endpoint=" + ElasticMq.endpoint(),
                "--data-processing.queues.file-processing-url=" + JOBS,
                "--data-processing.queues.security-details-url=" + DETAILS);
    functions.add(context);
    return context;
  }

  private JobId submitBhavcopy(AdmitSubmission submission) {
    String key = "runs/run_roles_1/raw/debt-bhavcopy/1/BSE_fgroup01012026.csv";
    return submit(
        submission,
        SubmissionEvents.bse("run_roles_1", "2026-01-01"),
        List.of(file("debt-bhavcopy", key, "csv", BhavcopyProcessingIT.golden())));
  }

  private JobId submitNsdl(AdmitSubmission submission) {
    String isin = NsdlProcessingIT.ISIN;
    List<Map<String, Object>> files = new ArrayList<>();
    NsdlProcessingIT.samples()
        .forEach(
            (suffix, content) ->
                files.add(
                    file(
                        suffix,
                        "runs/run_roles_2/raw/" + suffix + "/1/" + isin + "_" + suffix + ".json",
                        "json",
                        content)));
    return submit(submission, SubmissionEvents.nsdl("run_roles_2", isin), files);
  }

  /** Stores the manifest the fetch service would write, then admits the submission. */
  private static JobId submit(
      AdmitSubmission submission, Map<String, Object> event, List<Map<String, Object>> files) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) event.get("data"));
    data.remove("manifest");
    data.put("files", files);
    Map<String, Object> manifest = new LinkedHashMap<>(event);
    manifest.put("data", data);
    String runId = String.valueOf(data.get("run_id"));
    put("runs/" + runId + "/manifest.json", JSON.writeValueAsBytes(manifest));
    return submission.admit(SubmissionEvents.submissionOf(event), event).jobId();
  }

  /** Stores a source file and returns its manifest entry. */
  private static Map<String, Object> file(String jobId, String key, String format, byte[] content) {
    put(key, content);
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("job_id", jobId);
    file.put("bucket", SOURCE_BUCKET);
    file.put("key", key);
    file.put("format", format);
    file.put("sha256", sha256(content));
    file.put("size_bytes", content.length);
    return file;
  }

  private String statusOf(JobId job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
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
