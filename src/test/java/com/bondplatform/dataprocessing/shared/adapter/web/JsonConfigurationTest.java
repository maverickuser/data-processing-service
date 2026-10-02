package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Serialization part of test case I-READ-08, exercised on a mapper built with the customizer. */
class JsonConfigurationTest {

  private final JsonMapper json = configuredMapper();

  @Test
  void writesDecimalsExactlyInPlainNotation() {
    assertThat(json.writeValueAsString(new BigDecimal("1E+3"))).isEqualTo("1000");
    assertThat(json.writeValueAsString(new BigDecimal("1598800.00"))).isEqualTo("1598800.00");
    assertThat(json.writeValueAsString(new BigDecimal("8.123456789012345678901234567890")))
        .isEqualTo("8.123456789012345678901234567890");
  }

  @Test
  void readsFractionalNumbersWithoutFloatingPoint() {
    Object value = json.readValue("{\"amount\": 89400.10}", Map.class).get("amount");

    assertThat(value).isEqualTo(new BigDecimal("89400.10"));
  }

  @Test
  void writesInstantsAsUtcTextAndDatesAsIsoText() {
    assertThat(json.writeValueAsString(Instant.parse("2026-09-27T14:31:02Z")))
        .isEqualTo("\"2026-09-27T14:31:02Z\"");
    assertThat(json.writeValueAsString(LocalDate.of(2026, 1, 1))).isEqualTo("\"2026-01-01\"");
  }

  private static JsonMapper configuredMapper() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new JsonConfiguration().exactNumbers().customize(builder);
    return builder.build();
  }
}
