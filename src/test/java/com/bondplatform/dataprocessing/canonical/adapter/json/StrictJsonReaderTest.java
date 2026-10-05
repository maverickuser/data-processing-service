package com.bondplatform.dataprocessing.canonical.adapter.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.domain.JsonRead;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonArray;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonBoolean;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNull;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNumber;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonObject;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonString;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StrictJsonReaderTest {

  private final StrictJsonReader reader = new StrictJsonReader();

  @Test
  void readsEveryKindOfValueKeepingPropertyOrder() {
    JsonValue root =
        parsed(
            """
            {"issuerName": " Acme ", "issuePrice": 1000, "flag": true, "off": false,
             "remarks": null, "listingDetails": [{"exchangeName": "BSE"}], "empty": {}}
            """);

    assertThat(root)
        .isEqualTo(
            new JsonObject(
                Map.of(
                    "issuerName", new JsonString(" Acme "),
                    "issuePrice", new JsonNumber(new BigDecimal("1000")),
                    "flag", new JsonBoolean(true),
                    "off", new JsonBoolean(false),
                    "remarks", new JsonNull(),
                    "listingDetails",
                        new JsonArray(
                            List.of(new JsonObject(Map.of("exchangeName", new JsonString("BSE"))))),
                    "empty", new JsonObject(Map.of()))));
    assertThat(((JsonObject) root).properties().keySet())
        .containsExactly(
            "issuerName", "issuePrice", "flag", "off", "remarks", "listingDetails", "empty");
  }

  // U-JSON-12
  @ParameterizedTest
  @CsvSource({
    "89400, 89400",
    "89400.00, 89400.00",
    "8.94, 8.94",
    "0.1, 0.1",
    "-0.0, -0.0",
    "1.5E+3, 1.5E+3",
    "123456789012345678901234567890.123456789, 123456789012345678901234567890.123456789"
  })
  void readsNumbersExactlyFromTheirText(String json, String expected) {
    assertThat(parsed("{\"n\": " + json + "}"))
        .isEqualTo(new JsonObject(Map.of("n", new JsonNumber(new BigDecimal(expected)))));
  }

  // U-JSON-12
  @Test
  void wholeAndFractionalFormsOfOneNumberCompareEqual() {
    BigDecimal whole = number(parsed("{\"n\": 89400}"));
    BigDecimal fractional = number(parsed("{\"n\": 89400.00}"));

    assertThat(whole).isEqualByComparingTo(fractional);
  }

  @Test
  void readsAnyJsonValueAsTheRoot() {
    assertThat(parsed("[1, \"a\"]"))
        .isEqualTo(new JsonArray(List.of(new JsonNumber(BigDecimal.ONE), new JsonString("a"))));
    assertThat(parsed(" null ")).isEqualTo(new JsonNull());
  }

  @Test
  void ignoresLeadingByteOrderMark() {
    assertThat(parsed("﻿{\"a\": 1}"))
        .isEqualTo(new JsonObject(Map.of("a", new JsonNumber(BigDecimal.ONE))));
  }

  // U-JSON-07
  @Test
  void repeatedPropertyInOneObjectMakesTheFileMalformed() {
    assertThat(read("{\n  \"rating\": \"AA\",\n  \"rating\": \"AAA\"\n}"))
        .isEqualTo(
            new JsonRead.Malformed(
                "The property 'rating' appears twice in one object at line 3, column 3"));
    assertThat(read("{\"a\": [{\"b\": 1, \"b\": 1}]}")).isInstanceOf(JsonRead.Malformed.class);
  }

  // U-JSON-07
  @Test
  void sameNameInDifferentObjectsIsNotRepeated() {
    assertThat(
            read(
                "{\"rating\": {\"rating\": \"AA\"}, \"list\": [{\"rating\": 1}, {\"rating\": 2}]}"))
        .isInstanceOf(JsonRead.Parsed.class);
  }

  // U-JSON-07
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"a\": }",
        "{\"a\": 1",
        "{'a': 1}",
        "{\"a\": 01}",
        "{\"a\": NaN}",
        "{\"a\": 1,}",
        "[1, 2",
        "// comment\n{}"
      })
  void malformedJsonIsReportedWithItsPositionOnly(String json) {
    assertThat(read(json))
        .isInstanceOfSatisfying(
            JsonRead.Malformed.class,
            malformed ->
                assertThat(malformed.detail())
                    .startsWith("The file is not well-formed JSON at line 1, column ")
                    .doesNotContain("'a'")
                    .doesNotContain("Unexpected"));
  }

  // U-JSON-07
  @Test
  void contentAfterTheValueMakesTheFileMalformed() {
    assertThat(read("{\"a\": 1}\n{\"b\": 2}"))
        .isEqualTo(
            new JsonRead.Malformed("More content follows the JSON value at line 2, column 1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  \n", "﻿"})
  void fileWithoutValueIsMalformed(String content) {
    assertThat(read(content)).isEqualTo(new JsonRead.Malformed("The file holds no JSON value"));
  }

  @Test
  void nestingDeeperThanTheLimitIsMalformed() {
    int limit = StrictJsonReader.MAX_NESTING_DEPTH;

    assertThat(read("[".repeat(limit) + "]".repeat(limit))).isInstanceOf(JsonRead.Parsed.class);
    assertThat(read("[".repeat(limit + 1) + "]".repeat(limit + 1)))
        .isEqualTo(
            new JsonRead.Malformed(
                "The file exceeds a limit of the JSON reader, such as nesting deeper than 32"
                    + " levels"));
  }

  @Test
  void fileThatIsNotUtf8IsMalformed() {
    byte[] latin1 = "{\"issuerName\": \"Société\"}".getBytes(StandardCharsets.ISO_8859_1);

    assertThat(reader.read(latin1))
        .isEqualTo(new JsonRead.Malformed("The file is not valid UTF-8"));
  }

  private JsonRead read(String json) {
    return reader.read(json.getBytes(StandardCharsets.UTF_8));
  }

  private JsonValue parsed(String json) {
    JsonRead read = read(json);
    assertThat(read).isInstanceOf(JsonRead.Parsed.class);
    return ((JsonRead.Parsed) read).root();
  }

  private static BigDecimal number(JsonValue root) {
    return ((JsonNumber) ((JsonObject) root).properties().getOrDefault("n", new JsonNull()))
        .value();
  }
}
