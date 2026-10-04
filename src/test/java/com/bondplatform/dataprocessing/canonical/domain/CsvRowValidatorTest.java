package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Test cases U-CSV-08..10 and the field rules of LLD section 5.2. */
class CsvRowValidatorTest {

  private final CsvRowValidator validator =
      new CsvRowValidator(BhavcopyContract.CONTRACT, RuleRegistry.standard());

  @Test
  void validRowPassesWithEveryFieldInContractOrder() {
    CanonicalRecord record = validator.validate(row(Map.of()));

    assertThat(record.recordNumber()).isEqualTo(2);
    assertThat(record.validationStatus()).isEqualTo(ValidationStatus.PASSED);
    assertThat(record.rowErrors()).isEmpty();
    assertThat(record.fields().keySet())
        .containsExactlyElementsOf(
            BhavcopyContract.CONTRACT.fields().stream()
                .map(SourceContract.CsvField::name)
                .toList());
  }

  @Test
  void groupedPriceKeepsRawValueAndScale() {
    CanonicalField close = field(Map.of("close_price", " 1,234.5600 "), "close_price");

    assertThat(close)
        .isEqualTo(
            new CanonicalField(
                "close_price",
                "Close Price",
                6,
                " 1,234.5600 ",
                "1234.5600",
                "1234.5600",
                FieldType.DECIMAL,
                ValidationStatus.PASSED,
                List.of()));
  }

  // U-CSV-08
  @Test
  void blankOptionalFieldPassesAsNull() {
    CanonicalField open = field(Map.of("open_price", "   "), "open_price");

    assertThat(open.validationStatus()).isEqualTo(ValidationStatus.PASSED);
    assertThat(open.rawValue()).isEqualTo("   ");
    assertThat(open.normalizedValue()).isNull();
    assertThat(open.parsedValue()).isNull();
  }

  // U-CSV-08
  @Test
  void blankIsinFailsAsRequiredValueMissing() {
    CanonicalRecord record = validator.validate(row(Map.of("isin", " ")));

    assertThat(record.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    CanonicalField isin = record.field("isin");
    assertThat(isin.parsedValue()).isNull();
    assertThat(isin.errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.REQUIRED_VALUE_MISSING);
  }

  // U-CSV-08
  @Test
  void securityCodeKeepsLeadingZerosAndIsinIsUppercased() {
    assertThat(field(Map.of("security_code", " 000123 "), "security_code").parsedValue())
        .isEqualTo("000123");
    assertThat(field(Map.of("isin", " ine121a07qy9 "), "isin").parsedValue())
        .isEqualTo("INE121A07QY9");
  }

  @ParameterizedTest
  @CsvSource({
    "'14.00', 14",
    "'1,14,200', 114200",
    "'1,234,567', 1234567",
    "'0', 0",
  })
  void wholeNumbersParseExactly(String raw, String parsed) {
    CanonicalField volume = field(Map.of("traded_volume", raw), "traded_volume");

    assertThat(volume.validationStatus()).isEqualTo(ValidationStatus.PASSED);
    assertThat(volume.parsedValue()).isEqualTo(parsed);
  }

  @ParameterizedTest
  @CsvSource({
    "traded_volume, '14.50', NOT_WHOLE_NUMBER",
    "number_of_trades, 'ten', INVALID_DECIMAL",
    "turnover, '1,2345.00', INVALID_NUMBER_GROUPING",
    "turnover, '1.2e3', INVALID_DECIMAL",
    "face_value, '₹100', INVALID_DECIMAL",
    "turnover, '1\"2', INVALID_DECIMAL",
  })
  void unparseableNumbersFailWithoutParsedValue(String name, String raw, ErrorCode code) {
    CanonicalField field = field(Map.of(name, raw), name);

    assertThat(field.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(field.rawValue()).isEqualTo(raw);
    assertThat(field.parsedValue()).isNull();
    assertThat(field.errors()).extracting(ValidationIssue::code).containsExactly(code);
  }

  @Test
  void negativeNumberFailsButKeepsItsParsedValue() {
    CanonicalField volume = field(Map.of("traded_volume", "-1,000"), "traded_volume");

    assertThat(volume.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(volume.parsedValue()).isEqualTo("-1000");
    assertThat(volume.errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.NEGATIVE_VALUE);
  }

  // U-CSV-09
  @Test
  void everyApplicableErrorOnTheRowIsReported() {
    CanonicalRecord record =
        validator.validate(
            row(
                Map.of(
                    "isin", "",
                    "traded_volume", "1.5",
                    "turnover", "-3",
                    "high_price", "90",
                    "open_price", "95",
                    "close_price", "80")));

    assertThat(record.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(record.fields().values())
        .filteredOn(field -> field.validationStatus() == ValidationStatus.FAILED)
        .extracting(CanonicalField::name)
        .containsExactlyInAnyOrder("isin", "traded_volume", "turnover");
    assertThat(record.rowErrors())
        .extracting(ValidationIssue::message)
        .containsExactly(
            "high_price must be greater than or equal to low_price.",
            "open_price must be greater than or equal to low_price.",
            "open_price must be less than or equal to high_price.",
            "close_price must be greater than or equal to low_price.");
    assertThat(record.rowErrors())
        .extracting(ValidationIssue::code)
        .containsOnly(ErrorCode.PRICE_INCONSISTENT);
  }

  // U-CSV-10: each rule has a passing and a failing case; low is 99 and high 101 by default.
  @ParameterizedTest
  @CsvSource({
    "high_price, 99, ''",
    "high_price, 98.99, high_price must be greater than or equal to low_price.",
    "open_price, 99, ''",
    "open_price, 98, open_price must be greater than or equal to low_price.",
    "open_price, 101.00, ''",
    "open_price, 101.01, open_price must be less than or equal to high_price.",
    "close_price, 99.0, ''",
    "close_price, 98, close_price must be greater than or equal to low_price.",
    "close_price, 101, ''",
    "close_price, 102, close_price must be less than or equal to high_price.",
  })
  void eachPriceRulePassesAndFails(String name, String raw, String message) {
    Map<String, String> values = new HashMap<>(Map.of(name, raw));
    if (name.equals("high_price")) {
      values.put("open_price", "");
      values.put("close_price", "");
    }

    List<String> messages =
        validator.validate(row(values)).rowErrors().stream().map(ValidationIssue::message).toList();

    assertThat(messages).isEqualTo(message.isEmpty() ? List.of() : List.of(message));
  }

  // U-CSV-10: no dependent comparison error when an operand is blank, unparseable, or negative.
  @ParameterizedTest
  @CsvSource({"''", "'abc'", "'-5'", "'1,00.0'"})
  void priceRulesSkipOperandsThatAreNotValidNumbers(String low) {
    CanonicalRecord record =
        validator.validate(row(Map.of("low_price", low, "high_price", "-10", "open_price", "50")));

    assertThat(record.rowErrors()).isEmpty();
  }

  @Test
  void rowWithoutSelectedCellIsRefused() {
    Map<String, CsvCell> cells = new HashMap<>(row(Map.of()).cells());
    cells.remove("turnover");

    assertThatThrownBy(() -> validator.validate(new CsvRow(7, cells)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Record 7 has no cell for turnover");
  }

  @Test
  void typeCsvDoesNotSupportIsRefused() {
    SourceContract.Csv contract =
        new SourceContract.Csv(
            BhavcopyContract.CONTRACT.id(),
            BhavcopyContract.CONTRACT.dataset(),
            1,
            List.of(
                new SourceContract.CsvField(
                    "listed_on", "Listed On", FieldType.DATE, false, List.of("trim"), List.of())),
            List.of(),
            "listed_on");

    assertThatThrownBy(() -> new CsvRowValidator(contract, RuleRegistry.standard()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CSV field listed_on has unsupported type DATE");
  }

  @Test
  void unselectedFieldIsRefused() {
    CanonicalRecord record = validator.validate(row(Map.of()));

    assertThatThrownBy(() -> record.field("extra"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("No selected field extra");
  }

  @Test
  void textFieldHasNoNumber() {
    assertThat(field(Map.of(), "isin").validNumber()).isEmpty();
    assertThat(field(Map.of(), "turnover").validNumber()).isPresent();
  }

  private CanonicalField field(Map<String, String> overrides, String name) {
    return validator.validate(row(overrides)).field(name);
  }

  private static CsvRow row(Map<String, String> overrides) {
    return BhavcopyContract.row(2, overrides);
  }
}
