package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs a real API Gateway HTTP API event through the Lambda entry point and the web layer. */
class ApiGatewayHandlerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void unknownRouteReturnsNotFoundProblem() throws IOException {
    ApiGatewayHandler handler = new ApiGatewayHandler();
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (InputStream event =
        getClass().getResourceAsStream("/fixtures/lambda/http-api-get-unknown-route.json")) {
      handler.handleRequest(event, output, new FixedLambdaContext());
    }

    JsonNode response = JSON.readTree(output.toString(StandardCharsets.UTF_8));
    assertThat(response.get("statusCode").asInt()).isEqualTo(404);
    assertThat(response.get("headers").get("Content-Type").asString())
        .startsWith("application/problem+json");
    JsonNode problem = JSON.readTree(response.get("body").asString());
    assertThat(problem.get("type").asString()).isEqualTo("urn:bond-platform:problem:not-found");
    assertThat(problem.get("title").asString()).isEqualTo("Not Found");
    assertThat(problem.get("status").asInt()).isEqualTo(404);
    assertThat(problem.get("code").asString()).isEqualTo("NOT_FOUND");
    assertThat(problem.get("correlationId").asString()).isNotBlank();
    assertThat(problem.get("instance").asString()).startsWith("urn:uuid:");
  }
}
