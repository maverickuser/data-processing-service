package com.bondplatform.dataprocessing.review.adapter.web;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads the read OpenAPI document so tests can hold every read response against it. */
final class ReadOpenApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode DOCUMENT =
      JSON.readTree(Path.of("docs/specs/data-processing-service-read-openapi.json").toFile());
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private ReadOpenApi() {}

  /**
   * Returns how a response breaks the read OpenAPI document; empty when the operation documents its
   * status and content type and the body satisfies the documented schema. OpenAPI 3.1 schemas are
   * JSON Schema 2020-12, so they are checked as written, {@code format} keywords included.
   *
   * @param path the operation's path template, such as {@code /v1/securities/{isin}}
   */
  static List<String> violations(String path, int status, String contentType, String body) {
    JsonNode operation = DOCUMENT.path("paths").path(path).path("get");
    if (operation.isMissingNode()) {
      return List.of("GET " + path + " is not documented");
    }
    JsonNode response = resolved(operation.path("responses").path(String.valueOf(status)));
    if (response.isMissingNode()) {
      return List.of("Status " + status + " is not documented for GET " + path);
    }
    int parameters = contentType.indexOf(';');
    String mediaType =
        (parameters < 0 ? contentType : contentType.substring(0, parameters)).strip();
    JsonNode schema = response.path("content").path(mediaType).path("schema");
    if (schema.isMissingNode()) {
      return List.of(mediaType + " is not documented for " + status + " of GET " + path);
    }
    ObjectNode root = (ObjectNode) schema.deepCopy();
    root.set("components", DOCUMENT.get("components"));
    Schema validator = SCHEMAS.getSchema(root);
    List<String> violations = new ArrayList<>();
    validator
        .validate(
            body,
            InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
        .forEach(error -> violations.add(error.toString()));
    return violations;
  }

  /** Returns the paths and statuses the document lists for GET, as {@code "path status"}. */
  static List<String> documentedResponses() {
    List<String> documented = new ArrayList<>();
    DOCUMENT
        .get("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .path("get")
                    .path("responses")
                    .propertyNames()
                    .forEach(status -> documented.add(path.getKey() + " " + status)));
    return documented;
  }

  // A response may be a reference into components.responses
  private static JsonNode resolved(JsonNode response) {
    JsonNode reference = response.path("$ref");
    if (reference.isMissingNode()) {
      return response;
    }
    String name = reference.asString().substring("#/components/responses/".length());
    return DOCUMENT.path("components").path("responses").path(name);
  }
}
