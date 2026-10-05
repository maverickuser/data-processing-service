package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class JsonFieldReaderTest {

  private static final String PATH = "$.x";

  private final JsonFieldReader reader = new JsonFieldReader(RuleRegistry.standard());

  static Stream<Arguments> noUpdateValues() {
    return Stream.of(
        Arguments.of(new SourceValue.Missing(), FieldPresence.MISSING),
        Arguments.of(new SourceValue.Null(), FieldPresence.NULL),
        Arguments.of(text(""), FieldPresence.PLACEHOLDER),
        Arguments.of(text("   "), FieldPresence.PLACEHOLDER),
        Arguments.of(text(" - "), FieldPresence.PLACEHOLDER),
        Arguments.of(text("N.A."), FieldPresence.PLACEHOLDER));
  }

  @ParameterizedTest
  @MethodSource("noUpdateValues")
  void missingNullAndPlaceholdersAreNoUpdateForEveryType(
      SourceValue value, FieldPresence presence) {
    for (FieldType type :
        List.of(FieldType.TEXT, FieldType.DATE, FieldType.DECIMAL, FieldType.PERCENT)) {
      JsonCanonicalField field = reader.read("f", PATH, type, value);

      assertThat(field)
          .isEqualTo(
              new JsonCanonicalField(
                  "f",
                  PATH,
                  presence,
                  value,
                  null,
                  null,
                  type,
                  ValidationStatus.PASSED,
                  List.of()));
      assertThat(field.isUsable()).isFalse();
    }
  }

  // U-TEXT-01
  @Test
  void textIsTrimmedAndKeepsItsCase() {
    JsonCanonicalField field = read(FieldType.TEXT, text("  Non PSU "));

    assertThat(field.normalizedValue()).isEqualTo("Non PSU");
    assertThat(field.parsedValue()).isEqualTo("Non PSU");
    assertThat(field.rawValue()).isEqualTo(text("  Non PSU "));
    assertThat(field.presence()).isEqualTo(FieldPresence.PRESENT);
    assertThat(field.isUsable()).isTrue();
  }

  // U-TEXT-01
  @Test
  void textRejectsEveryOtherKindWithoutConverting() {
    assertThat(read(FieldType.TEXT, new SourceValue.Decimal(BigDecimal.TEN)).errors())
        .containsExactly(
            new ValidationIssue(ErrorCode.INVALID_TYPE, "Expected a string but found number."));
    assertThat(error(FieldType.TEXT, new SourceValue.Bool(true))).isEqualTo(ErrorCode.INVALID_TYPE);
    assertThat(error(FieldType.TEXT, structured(SourceValue.Structured.Kind.OBJECT)))
        .isEqualTo(ErrorCode.INVALID_TYPE);
    assertThat(error(FieldType.TEXT, structured(SourceValue.Structured.Kind.ARRAY)))
        .isEqualTo(ErrorCode.INVALID_TYPE);
    assertThat(read(FieldType.TEXT, new SourceValue.Bool(false)).parsedValue()).isNull();
  }

  @ParameterizedTest
  @CsvSource({"10-06-2019, 2019-06-10", "2019-06-10, 2019-06-10", "' 08-06-2029 ', 2029-06-08"})
  void dateBecomesIsoDate(String raw, String iso) {
    JsonCanonicalField field = read(FieldType.DATE, text(raw));

    assertThat(field.validationStatus()).isEqualTo(ValidationStatus.PASSED);
    assertThat(field.normalizedValue()).isEqualTo(raw.strip());
    assertThat(field.parsedValue()).isEqualTo(iso);
  }

  @Test
  void impossibleOrUnrecognizedDateIsRejectedKeepingItsText() {
    JsonCanonicalField impossible = read(FieldType.DATE, text("31-02-2019"));

    assertThat(impossible.errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_DATE);
    assertThat(impossible.normalizedValue()).isEqualTo("31-02-2019");
    assertThat(impossible.parsedValue()).isNull();
    assertThat(error(FieldType.DATE, text("10/06/2019"))).isEqualTo(ErrorCode.INVALID_DATE);
  }

  @Test
  void dateMustBeString() {
    assertThat(read(FieldType.DATE, new SourceValue.Decimal(new BigDecimal("20190610"))).errors())
        .containsExactly(
            new ValidationIssue(
                ErrorCode.INVALID_TYPE, "Expected a date string but found number."));
  }

  // U-JSON-12
  @Test
  void numberAndGroupedStringNormaliseToEqualValues() {
    JsonCanonicalField number = read(FieldType.DECIMAL, decimal("89400"));
    JsonCanonicalField grouped = read(FieldType.DECIMAL, text("89,400.00"));

    assertThat(number.parsedValue()).isEqualTo("89400");
    assertThat(grouped.normalizedValue()).isEqualTo("89400.00");
    assertThat(grouped.parsedValue()).isEqualTo("89400.00");
    assertThat(parsed(number)).isEqualByComparingTo(parsed(grouped));
  }

  @ParameterizedTest
  @CsvSource({"'12,34,567.89', 1234567.89", "'1,234,567', 1234567", "' 0 ', 0", "0.000, 0.000"})
  void decimalTextAcceptsWesternAndIndianGrouping(String raw, String expected) {
    assertThat(read(FieldType.DECIMAL, text(raw)).parsedValue()).isEqualTo(expected);
  }

  @Test
  void decimalNumberIsWrittenOutWithoutExponent() {
    JsonCanonicalField field = read(FieldType.DECIMAL, decimal("1.5E+3"));

    assertThat(field.normalizedValue()).isEqualTo("1500");
    assertThat(field.parsedValue()).isEqualTo("1500");
  }

  @Test
  void badDecimalTextIsRejected() {
    assertThat(error(FieldType.DECIMAL, text("8,94,00")))
        .isEqualTo(ErrorCode.INVALID_NUMBER_GROUPING);
    assertThat(error(FieldType.DECIMAL, text("Rs 100"))).isEqualTo(ErrorCode.INVALID_DECIMAL);
    assertThat(error(FieldType.DECIMAL, text("8.94%"))).isEqualTo(ErrorCode.INVALID_DECIMAL);
    assertThat(error(FieldType.DECIMAL, text("1e3"))).isEqualTo(ErrorCode.INVALID_DECIMAL);
  }

  @Test
  void hugeExponentIsRejectedWithoutWritingItOut() {
    JsonCanonicalField field = read(FieldType.DECIMAL, decimal("1e999999999"));

    assertThat(field.errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_DECIMAL);
    assertThat(field.normalizedValue()).isNull();
  }

  @Test
  void negativeNumberKeepsItsParsedValueAndFails() {
    JsonCanonicalField number = read(FieldType.DECIMAL, decimal("-5"));
    JsonCanonicalField grouped = read(FieldType.DECIMAL, text("-1,234.50"));

    assertThat(number.errors())
        .containsExactly(new ValidationIssue(ErrorCode.NEGATIVE_VALUE, "Expected zero or more."));
    assertThat(number.parsedValue()).isEqualTo("-5");
    assertThat(grouped.parsedValue()).isEqualTo("-1234.50");
    assertThat(grouped.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(grouped.isUsable()).isFalse();
  }

  @Test
  void numberMustBeNumericOrText() {
    assertThat(read(FieldType.DECIMAL, new SourceValue.Bool(true)).errors())
        .containsExactly(
            new ValidationIssue(
                ErrorCode.INVALID_TYPE, "Expected a number or numeric string but found boolean."));
    assertThat(error(FieldType.PERCENT, structured(SourceValue.Structured.Kind.ARRAY)))
        .isEqualTo(ErrorCode.INVALID_TYPE);
  }

  @ParameterizedTest
  @CsvSource({"8.94%, 8.94", "8.94, 8.94", "' 8.94 % ', 8.94", "0%, 0", "'1,000%', 1000"})
  void percentIsInPercentagePoints(String raw, String expected) {
    assertThat(read(FieldType.PERCENT, text(raw)).parsedValue()).isEqualTo(expected);
    assertThat(read(FieldType.PERCENT, decimal("8.94")).parsedValue()).isEqualTo("8.94");
  }

  @Test
  void negativePercentFails() {
    assertThat(error(FieldType.PERCENT, text("-1%"))).isEqualTo(ErrorCode.NEGATIVE_VALUE);
    assertThat(error(FieldType.PERCENT, text("%"))).isEqualTo(ErrorCode.INVALID_DECIMAL);
  }

  @ParameterizedTest
  @EnumSource(value = FieldType.class, names = "INTEGER")
  void jsonFieldsCannotBeIntegers(FieldType type) {
    assertThatThrownBy(() -> read(type, text("1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("JSON field f cannot have type INTEGER");
  }

  @Test
  void unreachableFieldIsReportedWithTheBlockingPath() {
    JsonCanonicalField field =
        JsonFieldReader.unreachable(
            "coupon_rate",
            "$.coupensVo.couponDetails.couponRate",
            FieldType.PERCENT,
            new JsonMatch.WrongStructure("$.coupensVo", "object", "string"));

    assertThat(field)
        .isEqualTo(
            new JsonCanonicalField(
                "coupon_rate",
                "$.coupensVo.couponDetails.couponRate",
                FieldPresence.MISSING,
                new SourceValue.Missing(),
                null,
                null,
                FieldType.PERCENT,
                ValidationStatus.FAILED,
                List.of(
                    new ValidationIssue(
                        ErrorCode.INVALID_TYPE,
                        "Expected an object at $.coupensVo but found string."))));
  }

  private JsonCanonicalField read(FieldType type, SourceValue value) {
    return reader.read("f", PATH, type, value);
  }

  private ErrorCode error(FieldType type, SourceValue value) {
    JsonCanonicalField field = read(type, value);
    assertThat(field.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(field.errors()).hasSize(1);
    return field.errors().get(0).code();
  }

  private static BigDecimal parsed(JsonCanonicalField field) {
    return new BigDecimal(Objects.requireNonNull(field.parsedValue()));
  }

  private static SourceValue text(String value) {
    return new SourceValue.Text(value);
  }

  private static SourceValue decimal(String value) {
    return new SourceValue.Decimal(new BigDecimal(value));
  }

  private static SourceValue structured(SourceValue.Structured.Kind kind) {
    return new SourceValue.Structured(kind);
  }
}
