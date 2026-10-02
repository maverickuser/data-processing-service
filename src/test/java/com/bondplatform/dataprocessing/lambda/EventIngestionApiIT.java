package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.domain.CanonicalJson;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * Test cases I-ADM-01, I-ADM-02, I-ADM-03, I-ADM-04, and I-ADM-06: submissions sent through the
 * Lambda entry point to an application that stores them in PostgreSQL.
 *
 * <p>The entry point starts its own application context, as it does in Lambda. It is pointed at the
 * shared test database through system properties, which stand in for the function's environment.
 */
class EventIngestionApiIT extends PostgresIntegrationTest {

  private static final String PATH = "/v1/event-ingestions";
  private static final String CLOUD_EVENT = "application/cloudevents+json";
  private static final List<String> DATABASE_PROPERTIES =
      List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password");

  private static @Nullable ApiGatewayHandler handler;

  @Autowired private Environment environment;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void startApplicationOnce() {
    if (handler == null) {
      for (String property : DATABASE_PROPERTIES) {
        System.setProperty(property, environment.getRequiredProperty(property));
      }
      handler = new ApiGatewayHandler();
    }
  }

  @AfterAll
  static void forgetTheDatabase() {
    DATABASE_PROPERTIES.forEach(System::clearProperty);
    handler = null;
  }

  // I-ADM-01, I-ADM-06
  @Test
  void acceptedSubmissionIsStoredAndAnsweredWithItsReceipt() throws IOException {
    HttpApiResponse response = submit("run_202", SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.header("Content-Type"))
        .startsWith(SubmissionOpenApi.documentedContentType(202));
    assertThat(SubmissionOpenApi.violations("AdmissionReceipt", response.body())).isEmpty();
    JsonNode receipt = response.json();
    String jobId = receipt.get("jobId").asString();
    assertThat(receipt.get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(receipt.get("eventId").asString()).isEqualTo("urn:bond-platform:submission:run_202");
    assertThat(receipt.get("runId").asString()).isEqualTo("run_202");
    assertThat(receipt.get("createdAt").asString()).endsWith("Z");
    assertThat(receipt.get("statusUrl").asString())
        .isEqualTo("https://processing.kagent.app/v1/processing-jobs/" + jobId);
    assertThat(response.header("Location")).isEqualTo(receipt.get("statusUrl").asString());
    assertThat(response.body()).doesNotContain("data-fetch-service-artifacts", "manifest.json");
    Map<String, Object> stored =
        jdbc.sql(
                """
                SELECT r.id::text AS id, r.status, r.source_contract_id,
                  e.payload ->> 'jobId' AS queued_job
                FROM data_processing.ingestion_requests r
                JOIN data_processing.outbox_events e ON e.ordering_key = r.acceptance_sequence
                """)
            .query()
            .singleRow();
    assertThat(stored)
        .containsEntry("id", jobId)
        .containsEntry("status", "QUEUED")
        .containsEntry("source_contract_id", "nsdl-security-json")
        .containsEntry("queued_job", jobId);
  }

  // I-ADM-02
  @Test
  void replayInAnotherKeyOrderReturnsTheSameReceiptAfterTheJobIsTerminal() throws IOException {
    Map<String, Object> event = SubmissionEvents.bse("run_101", "2026-09-21");
    HttpApiResponse first = submit("run_101", event);
    jdbc.sql("UPDATE data_processing.ingestion_requests SET status = 'FAILED'").update();

    HttpApiResponse replay =
        send(
            HttpApiEvent.post(PATH)
                .header("idempotency-key", "run_101")
                .body(
                    CLOUD_EVENT + "; charset=utf-8",
                    "  " + CanonicalJson.of(new TreeMap<>(event).descendingMap()) + "\n"));

    assertThat(first.status()).isEqualTo(202);
    assertThat(replay.status()).isEqualTo(202);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.header("Location")).isEqualTo(first.header("Location"));
    assertThat(count("ingestion_requests")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  // I-ADM-03, I-ADM-06
  @Test
  void keyReusedWithDifferentContentIsConflict() throws IOException {
    submit("run_202", SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> changed = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    changed.put("time", "2026-09-27T14:30:01Z");

    HttpApiResponse response = submit("run_202", changed);

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.header("Content-Type"))
        .startsWith(SubmissionOpenApi.documentedContentType(409));
    assertThat(SubmissionOpenApi.violations("Problem", response.body())).isEmpty();
    assertThat(response.json().get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(count("ingestion_requests")).isEqualTo(1);
  }

  @Test
  void numberWrittenDifferentlyIsDifferentContent() throws IOException {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    String body = CanonicalJson.of(event);
    send(HttpApiEvent.post(PATH).header("idempotency-key", "run_202").body(CLOUD_EVENT, body));

    HttpApiResponse response =
        send(
            HttpApiEvent.post(PATH)
                .header("idempotency-key", "run_202")
                .body(CLOUD_EVENT, body.replace("\"schema_version\":1", "\"schema_version\":1.0")));

    assertThat(body).contains("\"schema_version\":1");
    assertThat(response.status()).isEqualTo(409);
  }

  // I-ADM-04
  @Test
  void refusedSubmissionsStoreNothing() throws IOException {
    Map<String, Object> invalid = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    invalid.put("id", "urn:bond-platform:submission:run\0_202");
    String valid = CanonicalJson.of(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(submit("run_202", invalid).status()).isEqualTo(400);
    assertThat(submit("run_999", SubmissionEvents.nsdl("run_202", "INE121A07QY9")).status())
        .isEqualTo(400);
    assertThat(
            send(HttpApiEvent.post(PATH)
                    .header("idempotency-key", "run_202")
                    .body("application/json", valid))
                .status())
        .isEqualTo(415);
    assertThat(
            send(HttpApiEvent.post(PATH)
                    .header("idempotency-key", "run_202")
                    .body(
                        CLOUD_EVENT,
                        valid.replaceFirst("\\{", "{\"x\":\"" + "x".repeat(65_536) + "\",")))
                .status())
        .isEqualTo(413);

    assertThat(count("ingestion_requests")).isZero();
    assertThat(count("outbox_events")).isZero();
  }

  // I-ADM-06
  @Test
  void submissionThatCannotTakeItsTurnInTimeIsServiceUnavailable()
      throws IOException, SQLException {
    HttpApiResponse response;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        lock.setString(1, "isin:INE121A07QY9");
        lock.execute();
      }

      response = submit("run_202", SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

      holder.rollback();
    }

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.header("Retry-After")).isEqualTo("5");
    assertThat(response.header("Content-Type"))
        .startsWith(SubmissionOpenApi.documentedContentType(503));
    assertThat(SubmissionOpenApi.violations("Problem", response.body())).isEmpty();
    assertThat(count("ingestion_requests")).isZero();
    assertThat(submit("run_202", SubmissionEvents.nsdl("run_202", "INE121A07QY9")).status())
        .isEqualTo(202);
  }

  private HttpApiResponse submit(String idempotencyKey, Map<String, Object> event)
      throws IOException {
    return send(
        HttpApiEvent.post(PATH)
            .header("idempotency-key", idempotencyKey)
            .body(CLOUD_EVENT, CanonicalJson.of(event)));
  }

  private static HttpApiResponse send(HttpApiEvent event) throws IOException {
    return HttpApiResponse.of(java.util.Objects.requireNonNull(handler), event);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM data_processing." + table).query(Long.class).single();
  }
}
