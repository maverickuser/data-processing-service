package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.domain.CanonicalJson;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Test cases I-ADM-04 and, for the error responses, I-ADM-06: submissions refused before anything
 * is stored, sent through the Lambda entry point. These need no database, which also shows that a
 * refused submission never reaches it.
 */
class EventIngestionRejectionsIT {

  private static final String PATH = "/v1/event-ingestions";
  private static final String CLOUD_EVENT = "application/cloudevents+json";

  /** Nothing listens here, and the attempt to connect gives up quickly. */
  private static final Map<String, String> UNREACHABLE_DATABASE =
      Map.of(
          "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unreachable",
          "spring.datasource.hikari.connection-timeout", "250");

  private static ApiGatewayHandler handler;

  @BeforeAll
  static void startApplicationWithoutDatabase() {
    UNREACHABLE_DATABASE.forEach(System::setProperty);
    handler = new ApiGatewayHandler();
  }

  @AfterAll
  static void forgetTheDatabaseSettings() {
    UNREACHABLE_DATABASE.keySet().forEach(System::clearProperty);
  }

  static Stream<Arguments> refusedSubmissions() {
    String valid = CanonicalJson.of(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    return Stream.of(
        refused("plain JSON content type", submission("application/json", valid), 415),
        refused(
            "batch of events",
            submission("application/cloudevents-batch+json", "[" + valid + "]"),
            415),
        refused(
            "another character set", submission(CLOUD_EVENT + "; charset=ISO-8859-1", valid), 415),
        refused(
            "no content type",
            HttpApiEvent.post(PATH)
                .header("idempotency-key", "run_202")
                .bodyWithoutContentType(valid),
            415),
        refused("no body", HttpApiEvent.post(PATH).header("idempotency-key", "run_202"), 415),
        refused(
            "body over 64 KiB",
            submission(CLOUD_EVENT, "{\"padding\": \"" + "x".repeat(65_536) + "\"}"),
            413),
        refused("malformed JSON", submission(CLOUD_EVENT, "{not json"), 400),
        refused(
            "repeated name",
            submission(CLOUD_EVENT, valid.replaceFirst("\\{", "{\"id\": \"other\", ")),
            400),
        refused("an array", submission(CLOUD_EVENT, "[" + valid + "]"), 400),
        refused(
            "deeply nested body", submission(CLOUD_EVENT, "{\"a\": " + "[".repeat(30_000)), 400),
        refused("empty object", submission(CLOUD_EVENT, "{}"), 400),
        refused("missing key", HttpApiEvent.post(PATH).body(CLOUD_EVENT, valid), 400));
  }

  // I-ADM-04, I-ADM-06
  @ParameterizedTest
  @MethodSource("refusedSubmissions")
  void refusedSubmissionIsDocumentedProblem(HttpApiEvent event, int status) throws IOException {
    HttpApiResponse response = HttpApiResponse.of(handler, event);

    assertThat(response.status()).isEqualTo(status);
    assertThat(response.header("Content-Type"))
        .startsWith(SubmissionOpenApi.documentedContentType(status));
    assertThat(SubmissionOpenApi.violations("Problem", response.body())).isEmpty();
    assertThat(response.json().get("status").asInt()).isEqualTo(status);
  }

  // I-ADM-04
  @Test
  void invalidSubmissionNamesEachFaultWithItsPlace() throws IOException {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    event.remove("subject");
    event.put("specversion", "0.3");

    HttpApiResponse response =
        HttpApiResponse.of(
            handler,
            HttpApiEvent.post(PATH)
                .header("idempotency-key", "run_999")
                .body(CLOUD_EVENT, CanonicalJson.of(event)));

    assertThat(response.status()).isEqualTo(400);
    assertThat(SubmissionOpenApi.violations("Problem", response.body())).isEmpty();
    JsonNode errors = response.json().get("errors");
    assertThat(errors.toString())
        .contains("{\"location\":\"body\",\"pointer\":\"/subject\",\"code\":\"REQUIRED\"")
        .contains(
            "{\"location\":\"body\",\"pointer\":\"/specversion\",\"code\":\"UNSUPPORTED_VALUE\"")
        .contains("{\"location\":\"header\",\"header\":\"Idempotency-Key\",\"code\":\"MISMATCH\"")
        .doesNotContain("null");
  }

  // I-ADM-06
  @Test
  void validSubmissionIsServiceUnavailableWhileTheDatabaseCannotBeReached() throws IOException {
    HttpApiResponse response =
        HttpApiResponse.of(
            handler,
            submission(
                CLOUD_EVENT, CanonicalJson.of(SubmissionEvents.nsdl("run_202", "INE121A07QY9"))));

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.header("Retry-After")).isEqualTo("5");
    assertThat(response.header("Content-Type"))
        .startsWith(SubmissionOpenApi.documentedContentType(503));
    assertThat(SubmissionOpenApi.violations("Problem", response.body())).isEmpty();
    assertThat(response.body()).doesNotContain("127.0.0.1", "unreachable");
  }

  @Test
  void malformedBodyIsNotEchoedBack() throws IOException {
    HttpApiResponse response =
        HttpApiResponse.of(handler, submission(CLOUD_EVENT, "{\"password\": \"hunter2\""));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).doesNotContain("hunter2").doesNotContain("password");
  }

  @Test
  void theSchemaCheckItselfNoticesBodyThatBreaksTheContract() {
    assertThat(SubmissionOpenApi.violations("Problem", "{\"status\": 400}")).isNotEmpty();
    assertThat(SubmissionOpenApi.violations("AdmissionReceipt", "{\"status\": \"QUEUED\"}"))
        .isNotEmpty();
  }

  private static HttpApiEvent submission(String contentType, String body) {
    return HttpApiEvent.post(PATH).header("idempotency-key", "run_202").body(contentType, body);
  }

  private static Arguments refused(String name, HttpApiEvent event, int status) {
    return Arguments.of(Named.of(name, event), status);
  }
}
