package com.bondplatform.dataprocessing.canonical.adapter.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The canonical line shape of LLD section 4.2. */
class CanonicalLineWriterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<CanonicalRow> ROWS = GoldenBhavcopy.rows();

  @Test
  void acceptedRowCarriesRunSourceAndEveryField() {
    String line = CanonicalLineWriter.line(GoldenBhavcopy.RUN, ROWS.get(0));

    assertThat(line).doesNotContain("\n");
    JsonNode node = JSON.readTree(line);
    assertThat(node.get("jobId").asString()).isEqualTo("0190f3a0-0000-7000-8000-000000000001");
    assertThat(node.get("attemptNumber").asInt()).isEqualTo(2);
    assertThat(node.get("tradeDate").asString()).isEqualTo("2026-01-01");
    assertThat(node.get("source").get("bucket").asString())
        .isEqualTo("data-fetch-service-artifacts");
    assertThat(node.get("source").get("key").asString()).endsWith("BSE_fgroup01012026.csv");
    assertThat(node.get("source").get("recordNumber").asLong()).isEqualTo(2);
    assertThat(node.get("sourceContractVersion").asString()).isEqualTo("bse-debt-bhavcopy-csv-v1");
    assertThat(node.get("mappingContractVersion").asString())
        .isEqualTo("bse-debt-bhavcopy-mapping-v1");
    assertThat(node.get("fields").size()).isEqualTo(10);
    assertThat(node.get("rowValidationStatus").asString()).isEqualTo("PASSED");
    assertThat(node.get("disposition").asString()).isEqualTo("ACCEPTED");
    assertThat(node.get("supersededBy").isNull()).isTrue();
    assertThat(node.get("rowErrors").size()).isZero();
  }

  @Test
  void decimalIsTextWithItsTypeAndRawValue() {
    JsonNode close =
        JSON.readTree(CanonicalLineWriter.line(GoldenBhavcopy.RUN, ROWS.get(0)))
            .get("fields")
            .get("close_price");

    assertThat(close.get("sourceHeader").asString()).isEqualTo("Close Price");
    assertThat(close.get("columnIndex").asInt()).isEqualTo(7);
    assertThat(close.get("rawValue").asString()).isEqualTo("1000.2500");
    assertThat(close.get("normalizedValue").asString()).isEqualTo("1000.2500");
    assertThat(close.get("parsedValue").isString()).isTrue();
    assertThat(close.get("parsedValue").asString()).isEqualTo("1000.2500");
    assertThat(close.get("dataType").asString()).isEqualTo("DECIMAL");
    assertThat(close.get("validationStatus").asString()).isEqualTo("PASSED");
    assertThat(close.get("errors").size()).isZero();
  }

  @Test
  void supersededRowNamesWinnerAndCarriesItsError() {
    JsonNode node = JSON.readTree(CanonicalLineWriter.line(GoldenBhavcopy.RUN, ROWS.get(1)));

    assertThat(node.get("disposition").asString()).isEqualTo("QUARANTINED_SUPERSEDED");
    assertThat(node.get("supersededBy").asLong()).isEqualTo(6);
    assertThat(node.get("rowErrors").get(0).get("code").asString())
        .isEqualTo("DUPLICATE_ISIN_SUPERSEDED");
  }

  @Test
  void invalidFieldKeepsRawValueWithNullParsedValueAndError() {
    JsonNode node = JSON.readTree(CanonicalLineWriter.line(GoldenBhavcopy.RUN, ROWS.get(5)));
    JsonNode isin = node.get("fields").get("isin");

    assertThat(node.get("rowValidationStatus").asString()).isEqualTo("FAILED");
    assertThat(isin.get("rawValue").asString()).isEqualTo(" ");
    assertThat(isin.get("normalizedValue").isNull()).isTrue();
    assertThat(isin.get("parsedValue").isNull()).isTrue();
    assertThat(isin.get("errors").get(0).get("code").asString())
        .isEqualTo("REQUIRED_VALUE_MISSING");
    assertThat(isin.get("errors").get(0).get("message").asString()).isNotBlank();
  }
}
