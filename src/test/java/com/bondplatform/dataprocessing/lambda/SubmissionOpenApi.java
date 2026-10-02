package com.bondplatform.dataprocessing.lambda;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads the submission OpenAPI document so tests can hold responses against it. */
final class SubmissionOpenApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode DOCUMENT =
      JSON.readTree(Path.of("docs/specs/data-processing-service-openapi.json").toFile());
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private SubmissionOpenApi() {}

  /**
   * Returns how the JSON text breaks the named component schema; empty when it satisfies it.
   * OpenAPI 3.1 schemas are JSON Schema 2020-12, so they are checked as written.
   */
  static List<String> violations(String schemaName, String json) {
    ObjectNode root = JSON.createObjectNode();
    root.put("$ref", "#/components/schemas/" + schemaName);
    root.set("components", DOCUMENT.get("components"));
    Schema schema = SCHEMAS.getSchema(root);
    return schema.validate(json, InputFormat.JSON).stream().map(Object::toString).toList();
  }

  /** Returns the documented response of the submission operation for a status. */
  static JsonNode documentedResponse(int status) {
    JsonNode response =
        DOCUMENT
            .get("paths")
            .get("/v1/event-ingestions")
            .get("post")
            .get("responses")
            .get(String.valueOf(status));
    if (response == null) {
      throw new AssertionError("Status " + status + " is not documented for the submission");
    }
    return response;
  }

  /** Returns the one content type documented for a status of the submission operation. */
  static String documentedContentType(int status) {
    return documentedResponse(status).get("content").propertyNames().iterator().next();
  }
}
