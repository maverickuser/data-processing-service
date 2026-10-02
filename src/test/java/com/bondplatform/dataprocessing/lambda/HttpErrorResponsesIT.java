package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends API Gateway events through the Lambda entry point and checks that every kind of request
 * error keeps its own status and has this service's problem shape.
 */
class HttpErrorResponsesIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ApiGatewayHandler handler;

  @BeforeAll
  static void startApplication() {
    handler = new ApiGatewayHandler();
  }

  static Stream<Arguments> requestErrors() {
    return Stream.of(
        error("unknown route", HttpApiEvent.get("/v1/unknown"), 404, "NOT_FOUND"),
        error("wrong method", HttpApiEvent.post("/test-probe/dated"), 405, "METHOD_NOT_ALLOWED"),
        error("missing parameter", HttpApiEvent.get("/test-probe/dated"), 400, "INVALID_REQUEST"),
        error(
            "malformed date",
            HttpApiEvent.get("/test-probe/dated").query("tradeDate=2026-13-45"),
            400,
            "INVALID_REQUEST"),
        error(
            "unacceptable response type",
            HttpApiEvent.get("/test-probe/dated")
                .query("tradeDate=2026-01-01")
                .header("accept", "application/xml"),
            406,
            "NOT_ACCEPTABLE"),
        error(
            "unsupported content type",
            HttpApiEvent.post("/test-probe/echo").body("text/plain", "hello"),
            415,
            "UNSUPPORTED_MEDIA_TYPE"),
        error(
            "malformed JSON body",
            HttpApiEvent.post("/test-probe/echo").body("application/json", "{not json"),
            400,
            "INVALID_REQUEST"),
        error(
            "unexpected failure", HttpApiEvent.get("/test-probe/failing"), 500, "INTERNAL_ERROR"));
  }

  @ParameterizedTest
  @MethodSource("requestErrors")
  void requestErrorKeepsItsStatusAndProblemShape(HttpApiEvent event, int status, String code)
      throws IOException {
    JsonNode response = send(event);

    assertThat(response.get("statusCode").asInt()).isEqualTo(status);
    JsonNode problem = JSON.readTree(response.get("body").asString());
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(problem.get("code").asString()).isEqualTo(code);
    assertThat(problem.get("type").asString()).startsWith("urn:bond-platform:problem:");
    assertThat(problem.get("title").asString()).isNotBlank();
    assertThat(problem.get("detail").asString()).isNotBlank().doesNotContain("secret");
    assertThat(problem.get("instance").asString()).startsWith("urn:uuid:");
    assertThat(problem.get("correlationId").asString()).isNotBlank();
  }

  @Test
  void validRequestSucceeds() throws IOException {
    JsonNode response = send(HttpApiEvent.get("/test-probe/dated").query("tradeDate=2026-01-01"));

    assertThat(response.get("statusCode").asInt()).isEqualTo(200);
    assertThat(JSON.readTree(response.get("body").asString()).get("tradeDate").asString())
        .isEqualTo("2026-01-01");
  }

  @Test
  void fractionalNumbersRoundTripExactly() throws IOException {
    JsonNode response =
        send(
            HttpApiEvent.post("/test-probe/echo")
                .body("application/json", "{\"amount\": 89400.10, \"large\": 1E+3}"));

    assertThat(response.get("statusCode").asInt()).isEqualTo(200);
    assertThat(response.get("body").asString()).contains("89400.10").contains("1000");
  }

  private static JsonNode send(HttpApiEvent event) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    handler.handleRequest(event.toStream(), output, new FixedLambdaContext());
    return JSON.readTree(output.toString(StandardCharsets.UTF_8));
  }

  private static Arguments error(String name, HttpApiEvent event, int status, String code) {
    return Arguments.of(Named.of(name, event), status, code);
  }
}
