package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test case U-TEXT-01. */
class TextFieldReaderTest {

  @Test
  void acceptsStringTrimmedWithCasePreserved() {
    assertThat(TextFieldReader.read(new SourceValue.Text("  Non PSU ")))
        .isEqualTo(new FieldResult.Valid<>("Non PSU"));
  }

  static Stream<Arguments> wrongKinds() {
    return Stream.of(
        Arguments.of(new SourceValue.Decimal(new BigDecimal("8.94")), "number"),
        Arguments.of(new SourceValue.Bool(true), "boolean"),
        Arguments.of(
            new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT, "{}"), "object"),
        Arguments.of(new SourceValue.Structured(SourceValue.Structured.Kind.ARRAY, "[]"), "array"));
  }

  @ParameterizedTest
  @MethodSource("wrongKinds")
  void rejectsOtherJsonKindsInsteadOfConvertingThem(SourceValue value, String kindName) {
    assertThat(TextFieldReader.read(value))
        .isEqualTo(
            new FieldResult.Rejected<>(
                ErrorCode.INVALID_TYPE, "Expected a string but found " + kindName + "."));
  }

  static Stream<Arguments> noValues() {
    return Stream.of(
        Arguments.of(new SourceValue.Missing(), FieldPresence.MISSING),
        Arguments.of(new SourceValue.Null(), FieldPresence.NULL),
        Arguments.of(new SourceValue.Text(" - "), FieldPresence.PLACEHOLDER),
        Arguments.of(new SourceValue.Text("N.A."), FieldPresence.PLACEHOLDER));
  }

  @ParameterizedTest
  @MethodSource("noValues")
  void reportsNoValueRatherThanAnError(SourceValue value, FieldPresence presence) {
    assertThat(TextFieldReader.read(value)).isEqualTo(new FieldResult.NoValue<>(presence));
  }

  @Test
  void namesEveryKindForErrorMessages() {
    assertThat(new SourceValue.Missing().kindName()).isEqualTo("missing");
    assertThat(new SourceValue.Null().kindName()).isEqualTo("null");
    assertThat(new SourceValue.Text("x").kindName()).isEqualTo("string");
  }
}
