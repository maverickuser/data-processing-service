package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-JSON-04. */
class JsonFilenameTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INE831R08076_ratings.json",
        "INE831R08076.json",
        "ine831r08076_isin-details.JSON",
        " INE831R08076 _cash_flows.json"
      })
  void nameGivesTheIsinBeforeTheFirstUnderscoreOrTheExtension(String fileName) {
    assertThat(JsonFilename.isinOf(fileName)).contains(new Isin("INE831R08076"));
  }

  @Test
  void noFormatOrCheckDigitRuleApplies() {
    assertThat(JsonFilename.isinOf("NOT-AN-ISIN_ratings.json")).contains(new Isin("NOT-AN-ISIN"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"_ratings.json", " _ratings.json", ".json", "INE831R08076_ratings.csv"})
  void nameIdentifyingNoSecurityGivesNone(String fileName) {
    assertThat(JsonFilename.isinOf(fileName)).isEmpty();
  }
}
