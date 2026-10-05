package com.bondplatform.dataprocessing.lambda;

import java.util.List;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a function wrote to standard output as CloudWatch would (LLD section 23.8): JSON log
 * lines, and embedded metric format lines.
 */
final class JsonLines {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private JsonLines() {}

  /** Returns the log line whose message starts with the given text. */
  static JsonNode log(CapturedOutput output, String message) {
    return all(output).stream()
        .filter(line -> line.path("message").asString("").startsWith(message))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No JSON log line starts with: " + message));
  }

  /** Returns the embedded metric format lines that record the given metric, in order. */
  static List<JsonNode> metric(CapturedOutput output, String name) {
    return all(output).stream().filter(line -> line.has("_aws") && line.has(name)).toList();
  }

  private static List<JsonNode> all(CapturedOutput output) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.startsWith("{"))
        .map(JsonLines::parse)
        .toList();
  }

  private static JsonNode parse(String line) {
    try {
      return JSON.readTree(line);
    } catch (JacksonException e) {
      return JSON.createObjectNode();
    }
  }
}
