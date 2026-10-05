package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Test cases U-NUM-01 to U-NUM-05. */
class NumberRulesTest {

  private final RuleRegistry registry = RuleRegistry.standard();
  private final Normalizer numberNormalizer =
      registry.normalizerFor(List.of("trim", "blankToNull", "normalizeGroupedNumber"));
  private final NumberValidator nonNegative = registry.numberValidatorFor(List.of("nonNegative"));

  // U-NUM-01
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          1234.56|1234.56
          1,234.56|1234.56
          1,23,456.78|123456.78
          12,34,567|1234567
          1,234,567.89|1234567.89
          999|999
          0|0
          0.00|0.00
          '  1,14,200.00  '|114200.00
          1,000|1000
          10,00,000|1000000
          """)
  void acceptsUngroupedWesternAndIndianGrouping(String text, String expected) {
    assertThat(numberNormalizer.normalize(text)).isEqualTo(new FieldResult.Valid<>(expected));
  }

  // U-NUM-02
  @ParameterizedTest
  @ValueSource(
      strings = {
        "1,2,34",
        "1,,234",
        ",123",
        "1234,567",
        "1,23,45",
        "1,2345",
        "0,123",
        "000,123",
        "01,234",
        "00,00,000",
        "0,00,000",
        ",",
        "123,45,678",
        "100,00,000",
        "1,234,56,789"
      })
  void rejectsMalformedGroupingWithoutStrippingCommas(String text) {
    assertThat(numberNormalizer.normalize(text))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_NUMBER_GROUPING));
  }

  // U-NUM-02
  @ParameterizedTest
  @ValueSource(
      strings = {
        "1e5",
        "₹100",
        "(100)",
        "abc",
        "+5",
        "1.",
        ".5",
        "1.2.3",
        "12 34",
        "a,bcd",
        "12,34.5,6",
        "1.234,56",
        "1,234.",
        "1,234.5.6",
        "1.5,000",
        "+1,234",
        "1,234abc",
        "$1,000",
        "-",
        "١٢٣"
      })
  void rejectsTextThatIsNotPlainDecimal(String text) {
    assertThat(numberNormalizer.normalize(text))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DECIMAL));
  }

  @ParameterizedTest
  @CsvSource({"007, 007", "0.50, 0.50", "-0, -0", "00, 00"})
  void ungroupedLeadingZerosAreLeftForTheParser(String text, String expected) {
    assertThat(numberNormalizer.normalize(text)).isEqualTo(new FieldResult.Valid<>(expected));
    assertThat(ExactNumbers.decimal(expected))
        .isEqualTo(new FieldResult.Valid<>(new BigDecimal(expected)));
  }

  @Test
  void numberLongerThanTheLimitIsRejectedQuickly() {
    String atLimit = "9".repeat(ExactNumbers.MAX_LENGTH);
    String overLimit = "9".repeat(5_000_000);

    assertThat(ExactNumbers.decimal(atLimit)).isInstanceOf(FieldResult.Valid.class);
    assertThat(ExactNumbers.decimal(overLimit))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DECIMAL));
    assertThat(ExactNumbers.wholeNumber(overLimit))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("at most 1000 characters"));
  }

  @Test
  void parsedNumberWhosePlainFormIsTooLongIsRejected() {
    assertThat(ExactNumbers.decimal(new BigDecimal("1e999999999")))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DECIMAL));
    assertThat(ExactNumbers.decimal(new BigDecimal("1e-999999999")))
        .isInstanceOf(FieldResult.Rejected.class);
    assertThat(ExactNumbers.decimal(new BigDecimal("9".repeat(ExactNumbers.MAX_LENGTH))))
        .isInstanceOf(FieldResult.Valid.class);
    assertThat(ExactNumbers.decimal(new BigDecimal("-" + "9".repeat(ExactNumbers.MAX_LENGTH))))
        .isInstanceOf(FieldResult.Rejected.class);
    assertThat(ExactNumbers.decimal(new BigDecimal("0." + "9".repeat(ExactNumbers.MAX_LENGTH - 2))))
        .isInstanceOf(FieldResult.Valid.class);
    assertThat(ExactNumbers.decimal(new BigDecimal("1.5E+3")))
        .isEqualTo(new FieldResult.Valid<>(new BigDecimal("1.5E+3")));
  }

  @Test
  void blankNumberHasNoValue() {
    assertThat(numberNormalizer.normalize("   "))
        .isEqualTo(new FieldResult.NoValue<>(FieldPresence.PLACEHOLDER));
  }

  // U-NUM-03
  @Test
  void keepsEveryDigitAndTheScale() {
    assertThat(ExactNumbers.decimal("1234.5600"))
        .isEqualTo(new FieldResult.Valid<>(new BigDecimal("1234.5600")));
    assertThat(ExactNumbers.decimal("0.123456789012345678901234567890123456789"))
        .isEqualTo(
            new FieldResult.Valid<>(new BigDecimal("0.123456789012345678901234567890123456789")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1,234.56", "1e5", "", "abc", "٨.٩٤"})
  void decimalParserAcceptsOnlyNormalizedText(String text) {
    assertThat(ExactNumbers.decimal(text))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DECIMAL));
  }

  // U-NUM-04
  @ParameterizedTest
  @CsvSource({
    "14, 14",
    "14.00, 14",
    "0, 0",
    "0.0, 0",
    "123456789012345678901234567890, 123456789012345678901234567890"
  })
  void wholeNumberAcceptsIntegerValuedDecimals(String text, BigInteger expected) {
    assertThat(ExactNumbers.wholeNumber(text)).isEqualTo(new FieldResult.Valid<>(expected));
  }

  // U-NUM-04
  @ParameterizedTest
  @ValueSource(strings = {"14.50", "0.01", "1.000001"})
  void wholeNumberRejectsFractionsInsteadOfTruncating(String text) {
    assertThat(ExactNumbers.wholeNumber(text))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.NOT_WHOLE_NUMBER));
  }

  @Test
  void wholeNumberRejectsTextThatIsNotNumeric() {
    assertThat(ExactNumbers.wholeNumber("many"))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DECIMAL));
  }

  // U-NUM-05
  @Test
  void negativeNumberParsesAndThenFailsTheNonNegativeRule() {
    assertThat(numberNormalizer.normalize("-1,234.50"))
        .isEqualTo(new FieldResult.Valid<>("-1234.50"));
    assertThat(ExactNumbers.decimal("-1234.50"))
        .isEqualTo(new FieldResult.Valid<>(new BigDecimal("-1234.50")));

    assertThat(nonNegative.validate(new BigDecimal("-1234.50")))
        .extracting(RuleViolation::code)
        .containsExactly(ErrorCode.NEGATIVE_VALUE);
  }

  // U-NUM-05
  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "0.01", "114200.00"})
  void zeroAndPositiveNumbersPassTheNonNegativeRule(String number) {
    assertThat(nonNegative.validate(new BigDecimal(number))).isEmpty();
  }

  @Test
  void noValidatorsMeansNoViolations() {
    assertThat(registry.numberValidatorFor(List.of()).validate(new BigDecimal("-1"))).isEmpty();
  }

  @Test
  void rejectsValidatorNameThatIsNotRegistered() {
    assertThatThrownBy(() -> registry.numberValidatorFor(List.of("lessThanSalary")))
        .isInstanceOf(UnknownRuleException.class)
        .hasMessage("No number validator named 'lessThanSalary'");
  }

  @Test
  void namingRuleOfTheWrongKindSaysSo() {
    assertThatThrownBy(() -> registry.numberValidatorFor(List.of("trim")))
        .hasMessage("Rule 'trim' exists but is not a number validator");
    assertThatThrownBy(() -> registry.normalizerFor(List.of("nonNegative")))
        .hasMessage("Rule 'nonNegative' exists but is not a normalizer");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          8.94%|8.94
          8.94 %|8.94
          8.94|8.94
          100%|100
          %|''
          """)
  void stripsOneTrailingPercentSign(String text, String expected) {
    Normalizer normalizer = registry.normalizerFor(List.of("trim", "stripTrailingPercent"));

    assertThat(normalizer.normalize(text)).isEqualTo(new FieldResult.Valid<>(expected));
  }

  @Test
  void percentTextNormalizesToPlainDecimal() {
    Normalizer percentNormalizer =
        registry.normalizerFor(
            List.of("trim", "stripTrailingPercent", "blankToNull", "normalizeGroupedNumber"));

    assertThat(percentNormalizer.normalize(" 8.94 % ")).isEqualTo(new FieldResult.Valid<>("8.94"));
    assertThat(percentNormalizer.normalize("%"))
        .isEqualTo(new FieldResult.NoValue<>(FieldPresence.PLACEHOLDER));
  }
}
