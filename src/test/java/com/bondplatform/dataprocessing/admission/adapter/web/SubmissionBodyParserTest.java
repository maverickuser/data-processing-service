package com.bondplatform.dataprocessing.admission.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubmissionBodyParserTest {

  @Test
  void readsObjectIntoPlainMapsAndLists() {
    Optional<Map<String, Object>> parsed =
        parse("{\"id\": \"a\", \"data\": {\"ok\": true, \"tags\": [\"x\", null]}}");

    assertThat(parsed).isPresent();
    assertThat(parsed.orElseThrow()).containsEntry("id", "a").containsKey("data");
    assertThat(parsed.orElseThrow().get("data"))
        .isEqualTo(Map.of("ok", true, "tags", java.util.Arrays.asList("x", null)));
  }

  @Test
  void keepsNumbersExactAndNeverAsFloatingPoint() {
    Map<String, Object> parsed =
        parse(
                "{\"whole\": 1, \"fraction\": 1.0, \"exponent\": 1e2, \"long\": 9007199254740993,"
                    + " \"huge\": 123456789012345678901234567890, \"tiny\": 1e-400}")
            .orElseThrow();

    assertThat(parsed.get("whole")).isEqualTo(1);
    assertThat(parsed.get("fraction")).isEqualTo(new BigDecimal("1.0"));
    assertThat(parsed.get("exponent")).isEqualTo(new BigDecimal("1E+2"));
    assertThat(parsed.get("long")).isEqualTo(9007199254740993L);
    assertThat(parsed.get("huge")).isEqualTo(new BigInteger("123456789012345678901234567890"));
    assertThat(parsed.get("tiny")).isEqualTo(new BigDecimal("1E-400"));
    assertThat(parsed.values()).noneMatch(value -> value instanceof Double);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "{not json",
        "null",
        "[]",
        "\"text\"",
        "42",
        "{\"a\": 1} {\"b\": 2}",
        "{\"a\": 1} trailing",
        "{\"a\": 1, \"a\": 2}",
        "{\"data\": {\"run_id\": \"x\", \"run_id\": \"y\"}}",
        "{\"a\": NaN}",
        "{\"a\": 01}",
        "{'a': 1}"
      })
  void bodyThatIsNotOneWellFormedObjectIsRefused(String body) {
    assertThat(parse(body)).isEmpty();
  }

  @Test
  void bodyThatIsNotUtf8IsRefused() {
    assertThat(SubmissionBodyParser.parse("{\"a\": \"é\"}".getBytes(StandardCharsets.ISO_8859_1)))
        .isEmpty();
    assertThat(SubmissionBodyParser.parse("{\"a\": 1}".getBytes(StandardCharsets.UTF_16)))
        .isEmpty();
  }

  @Test
  void nestingDeeperThanTheLimitIsRefusedWithoutExhaustingTheStack() {
    assertThat(parse(nested(SubmissionBodyParser.MAX_NESTING_DEPTH - 1))).isPresent();
    assertThat(parse(nested(SubmissionBodyParser.MAX_NESTING_DEPTH))).isEmpty();
    assertThat(parse(nested(30_000))).isEmpty();
  }

  @Test
  void keepsTextExactlyIncludingEscapes() {
    assertThat(parse("{\"a\": \"x\\u0000y\", \"b\": \"ü\"}").orElseThrow())
        .containsEntry("a", "x\0y")
        .containsEntry("b", "ü");
    assertThat(parse("{\"list\": [1, 2]}").orElseThrow()).containsEntry("list", List.of(1, 2));
  }

  /** Returns an object holding {@code depth} lists inside each other, so depth + 1 levels. */
  private static String nested(int depth) {
    return "{\"a\": " + "[".repeat(depth) + "]".repeat(depth) + "}";
  }

  private static Optional<Map<String, Object>> parse(String body) {
    return SubmissionBodyParser.parse(body.getBytes(StandardCharsets.UTF_8));
  }
}
