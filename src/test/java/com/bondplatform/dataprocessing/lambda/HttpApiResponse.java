package com.bondplatform.dataprocessing.lambda;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the Lambda entry point returned to API Gateway for one event. */
record HttpApiResponse(int status, JsonNode headers, String body) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Sends the event through the handler and reads the API Gateway response. */
  static HttpApiResponse of(ApiGatewayHandler handler, HttpApiEvent event) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    handler.handleRequest(event.toStream(), output, new FixedLambdaContext());
    JsonNode response = JSON.readTree(output.toString(StandardCharsets.UTF_8));
    return new HttpApiResponse(
        response.get("statusCode").asInt(),
        response.get("headers"),
        response.get("body").asString());
  }

  /** Returns a response header, or null when it is absent. */
  @Nullable String header(String name) {
    JsonNode value = headers.get(name);
    return value == null ? null : value.asString();
  }

  /** Returns the body as parsed JSON. */
  JsonNode json() {
    return JSON.readTree(body);
  }
}
