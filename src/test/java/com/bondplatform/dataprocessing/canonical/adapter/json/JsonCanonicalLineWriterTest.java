package com.bondplatform.dataprocessing.canonical.adapter.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonRejection;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonCanonicalLineWriterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonFileEvidence WITH_ERRORS =
      JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED));

  @Test
  void readFileLineCarriesRunSourceAndEveryPart() {
    JsonNode line = JSON.readTree(JsonCanonicalLineWriter.line(JsonEvidence.RUN, WITH_ERRORS));

    assertThat(line.get("jobId").asString()).isEqualTo("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
    assertThat(line.get("attemptNumber").asInt()).isEqualTo(1);
    assertThat(line.get("isin").asString()).isEqualTo("INE831R08076");
    assertThat(line.at("/source/bucket").asString()).isEqualTo(WITH_ERRORS.bucket());
    assertThat(line.at("/source/key").asString()).isEqualTo(WITH_ERRORS.key());
    assertThat(line.get("sourceContractVersion").asString()).isEqualTo("nsdl-security-json-v1");
    assertThat(line.get("mappingContractVersion").asString()).isEqualTo("nsdl-security-mapping-v1");
    assertThat(line.get("status").asString()).isEqualTo("READ");
    assertThat(line.has("fileErrors")).isFalse();
    assertThat(line.get("scalars")).hasSizeGreaterThan(2);
    assertThat(line.get("entries")).hasSize(3);
    assertThat(line.at("/structureIssues/0/path").asString()).isEqualTo("$.currentRatings");
    assertThat(line.at("/structureIssues/0/code").asString()).isEqualTo("INVALID_TYPE");
    assertThat(line.get("warnings")).isEmpty();
    assertThat(line.at("/ignoredValues/0/rawValue").decimalValue()).isEqualTo("100");
    assertThat(line.at("/ignoredValues/0/actionTaken").asString())
        .isEqualTo("Ignored supplied coverage.");
  }

  @Test
  void fieldKeepsRawValueWithItsTypeAndErrors() {
    JsonNode rate = scalar("coupon_rate");

    assertThat(rate.get("path").asString()).isEqualTo("$.coupensVo.couponDetails.couponRate");
    assertThat(rate.get("presence").asString()).isEqualTo("PRESENT");
    assertThat(rate.get("rawKind").asString()).isEqualTo("string");
    assertThat(rate.get("rawValue").asString()).isEqualTo("abc");
    assertThat(rate.get("parsedValue").isNull()).isTrue();
    assertThat(rate.get("dataType").asString()).isEqualTo("PERCENT");
    assertThat(rate.get("validationStatus").asString()).isEqualTo("FAILED");
    assertThat(rate.at("/errors/0/code").asString()).isNotEmpty();
    assertThat(rate.at("/errors/0/message").asString()).isNotEmpty();
  }

  @Test
  void missingFieldHasKindButNoRawValue() {
    JsonNode missing = scalar("issuer_name");

    assertThat(missing.get("presence").asString()).isEqualTo("MISSING");
    assertThat(missing.get("rawKind").asString()).isEqualTo("missing");
    assertThat(missing.has("rawValue")).isFalse();
  }

  @Test
  void entryKeepsCollectionPathDispositionAndFields() {
    JsonNode entry =
        JSON.readTree(JsonCanonicalLineWriter.line(JsonEvidence.RUN, WITH_ERRORS)).at("/entries/1");

    assertThat(entry.get("collection").asString()).isEqualTo("listings");
    assertThat(entry.get("path").asString()).isEqualTo("$.listingDetails[1]");
    assertThat(entry.get("disposition").asString()).isEqualTo("SKIPPED");
    assertThat(entry.at("/fields/1/presence").asString()).isEqualTo("PLACEHOLDER");
    assertThat(entry.at("/fields/1/errors")).isEmpty();
  }

  @Test
  void skippedFileLineHasOnlyItsError() {
    JsonNode line =
        JSON.readTree(JsonCanonicalLineWriter.line(JsonEvidence.RUN, JsonEvidence.skipped()));

    assertThat(line.get("status").asString()).isEqualTo("SKIPPED");
    assertThat(line.at("/fileErrors/0/code").asString()).isEqualTo("MALFORMED_JSON");
    assertThat(line.has("scalars")).isFalse();
    assertThat(line.has("ignoredValues")).isFalse();
  }

  @Test
  void recordOfEachRejectionIsItsEvidence() {
    List<JsonRejection> rejections = WITH_ERRORS.rejections();

    assertThat(record(rejections.get(0)).get("path").asString()).isEqualTo("$.currentRatings");
    assertThat(record(rejections.get(1)).get("name").asString()).isEqualTo("coupon_rate");
    assertThat(record(rejections.get(2)).get("disposition").asString()).isEqualTo("ACCEPTED");
    assertThat(record(rejections.get(3)).get("code").asString())
        .isEqualTo("CONFLICTING_COLLATERAL_DATA");
    assertThat(record(JsonEvidence.skipped().rejections().getFirst()).get("code").asString())
        .isEqualTo("MALFORMED_JSON");
  }

  @Test
  void structuredRawValueIsEmbeddedWhole() {
    JsonFileEvidence file =
        JsonEvidence.read(
            "{\"coupensVo\": {\"couponDetails\": {\"couponType\": {\"a\": [1.50, \"x\"]}}}}",
            List.of());

    JsonNode line = JSON.readTree(JsonCanonicalLineWriter.line(JsonEvidence.RUN, file));

    JsonNode type = scalar(line, "coupon_type");
    assertThat(type.get("rawKind").asString()).isEqualTo("object");
    assertThat(type.at("/rawValue/a/1").asString()).isEqualTo("x");
    assertThat(JsonCanonicalLineWriter.line(JsonEvidence.RUN, file))
        .contains("\"rawValue\":{\"a\":[1.50,\"x\"]}");
  }

  private static JsonNode scalar(String name) {
    return scalar(JSON.readTree(JsonCanonicalLineWriter.line(JsonEvidence.RUN, WITH_ERRORS)), name);
  }

  private static JsonNode scalar(JsonNode line, String name) {
    for (JsonNode field : line.get("scalars")) {
      if (field.get("name").asString().equals(name)) {
        return field;
      }
    }
    throw new AssertionError("No scalar " + name);
  }

  private static JsonNode record(JsonRejection rejection) {
    return JSON.readTree(JsonCanonicalLineWriter.record(rejection));
  }
}
