package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-PRES-01. */
class FieldPresenceTest {

  @Test
  void missingPathHasNoValue() {
    assertThat(FieldPresence.of(new SourceValue.Missing())).isEqualTo(FieldPresence.MISSING);
  }

  @Test
  void explicitNullHasNoValue() {
    assertThat(FieldPresence.of(new SourceValue.Null())).isEqualTo(FieldPresence.NULL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "\t\n", "-", " - ", "N.A.", "  N.A. "})
  void blankTextAndSourcePlaceholdersHaveNoValue(String text) {
    assertThat(FieldPresence.of(new SourceValue.Text(text))).isEqualTo(FieldPresence.PLACEHOLDER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Listed", "n.a.", "NA", "--", "- -", "0", "null", "8.94%"})
  void anyOtherTextIsPresent(String text) {
    assertThat(FieldPresence.of(new SourceValue.Text(text))).isEqualTo(FieldPresence.PRESENT);
  }

  @Test
  void noBreakSpaceAndByteOrderMarkAreNotWhitespace() {
    assertThat(FieldPresence.of(new SourceValue.Text("\u00A0"))).isEqualTo(FieldPresence.PRESENT);
    assertThat(FieldPresence.of(new SourceValue.Text("\uFEFF-"))).isEqualTo(FieldPresence.PRESENT);
  }

  @Test
  void numbersBooleansObjectsAndArraysArePresent() {
    assertThat(FieldPresence.of(new SourceValue.Decimal(BigDecimal.ZERO)))
        .isEqualTo(FieldPresence.PRESENT);
    assertThat(FieldPresence.of(new SourceValue.Bool(false))).isEqualTo(FieldPresence.PRESENT);
    assertThat(
            FieldPresence.of(new SourceValue.Structured(SourceValue.Structured.Kind.ARRAY, "[]")))
        .isEqualTo(FieldPresence.PRESENT);
  }

  @Test
  void onlyPresentValuesCanUpdateData() {
    assertThat(FieldPresence.PRESENT.isNoUpdate()).isFalse();
    assertThat(FieldPresence.MISSING.isNoUpdate()).isTrue();
    assertThat(FieldPresence.NULL.isNoUpdate()).isTrue();
    assertThat(FieldPresence.PLACEHOLDER.isNoUpdate()).isTrue();
  }
}
