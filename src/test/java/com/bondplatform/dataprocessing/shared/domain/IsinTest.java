package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-VAL-01. */
class IsinTest {

  @ParameterizedTest
  @ValueSource(strings = {"INE831R08076", " ine831r08076 ", "Ine831R08076\t"})
  void trimsAndUppercases(String rawValue) {
    assertThat(Isin.of(rawValue).value()).isEqualTo("INE831R08076");
  }

  @Test
  void spellingsOfTheSameIsinAreEqual() {
    assertThat(Isin.of(" ine831r08076")).isEqualTo(Isin.of("INE831R08076"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void rejectsBlank(String rawValue) {
    assertThatIllegalArgumentException().isThrownBy(() -> Isin.of(rawValue));
  }

  @ParameterizedTest
  @ValueSource(strings = {"X", "NOT-AN-ISIN", "INE831R0807", "123"})
  void appliesNoLengthFormatOrCheckDigitValidation(String rawValue) {
    assertThat(Isin.of(rawValue).value()).isEqualTo(rawValue);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " INE831R08076", "ine831r08076"})
  void constructorRejectsValuesThatAreNotNormalized(String value) {
    assertThatIllegalArgumentException().isThrownBy(() -> new Isin(value));
  }

  @Test
  void printsAsItsValue() {
    assertThat(Isin.of("INE831R08076")).hasToString("INE831R08076");
  }
}
