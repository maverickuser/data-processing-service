package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-VAL-02. */
class PercentTest {

  @ParameterizedTest
  @ValueSource(strings = {"8.94", "8.94%", " 8.94 % ", "8.940"})
  void parsesTextWithOrWithoutPercentSign(String text) {
    assertThat(Percent.parse(text)).isEqualTo(Percent.of(new BigDecimal("8.94")));
  }

  @Test
  void meansPercentagePointsRatherThanFraction() {
    assertThat(Percent.parse("8.94%").value()).isEqualByComparingTo("8.94");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "100", "250.5"})
  void acceptsZeroAndValuesAboveOneHundred(String text) {
    assertThat(Percent.parse(text).value()).isEqualByComparingTo(text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.01", "-5%"})
  void rejectsNegativeValues(String text) {
    assertThatIllegalArgumentException().isThrownBy(() -> Percent.parse(text));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "%",
        "abc",
        "8.94%%",
        "eight",
        "1e2",
        "1E+3%",
        "+5",
        ".5",
        "5.",
        "1,234.5",
        "٨.٩٤",
        "1e999999999"
      })
  void rejectsNonNumericText(String text) {
    assertThatIllegalArgumentException().isThrownBy(() -> Percent.parse(text));
  }

  @Test
  void rejectsNegativeNumber() {
    assertThatIllegalArgumentException().isThrownBy(() -> Percent.of(new BigDecimal("-0.01")));
  }

  @Test
  void keepsEveryDigitWithoutRounding() {
    assertThat(Percent.parse("8.123456789012345678901234567890").value())
        .isEqualTo(new BigDecimal("8.123456789012345678901234567890"));
  }

  @Test
  void numericallyEqualValuesAreEqualAndHashAlike() {
    Percent shortForm = Percent.parse("100");
    Percent longForm = Percent.parse("100.00");

    assertThat(shortForm).isEqualTo(longForm).hasSameHashCodeAs(longForm);
    assertThat(shortForm).isNotEqualTo(Percent.parse("99.99")).isNotEqualTo("100");
  }

  @Test
  void printsValueWithUnit() {
    assertThat(Percent.parse("8.94%")).hasToString("8.94 PERCENT");
  }
}
