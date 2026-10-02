package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuleRegistryTest {

  private static final FieldResult<String> NO_VALUE =
      new FieldResult.NoValue<>(FieldPresence.PLACEHOLDER);

  private final RuleRegistry registry = RuleRegistry.standard();

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      ignoreLeadingAndTrailingWhitespace = false,
      textBlock =
          """
          trim|  976009 |976009
          trim|\tINE0KH208019\t|INE0KH208019
          uppercase|ine0kh208019|INE0KH208019
          uppercase|ıstanbul|ISTANBUL
          blankToNull|kept|kept
          """)
  void appliesSingleNormalizer(String ruleName, String input, String expected) {
    assertThat(registry.normalizerFor(List.of(ruleName)).normalize(input))
        .isEqualTo(valid(expected));
  }

  @Test
  void blankToNullTreatsWhitespaceOnlyTextAsNoValue() {
    Normalizer normalizer = registry.normalizerFor(List.of("blankToNull"));

    assertThat(normalizer.normalize("")).isEqualTo(NO_VALUE);
    assertThat(normalizer.normalize("   ")).isEqualTo(NO_VALUE);
  }

  @Test
  void appliesStepsInContractOrder() {
    Normalizer isinNormalizer = registry.normalizerFor(List.of("trim", "blankToNull", "uppercase"));

    assertThat(isinNormalizer.normalize("  ine0kh208019 ")).isEqualTo(valid("INE0KH208019"));
  }

  @Test
  void skipsLaterStepsOnceTheFieldHasNoValue() {
    Normalizer isinNormalizer = registry.normalizerFor(List.of("trim", "blankToNull", "uppercase"));

    assertThat(isinNormalizer.normalize("   ")).isEqualTo(NO_VALUE);
  }

  @Test
  void emptyRuleListLeavesTextUnchanged() {
    assertThat(registry.normalizerFor(List.of()).normalize(" as is ")).isEqualTo(valid(" as is "));
  }

  @Test
  void preservesLeadingZerosAndInnerSpaces() {
    Normalizer normalizer = registry.normalizerFor(List.of("trim", "blankToNull"));

    assertThat(normalizer.normalize(" 000123 ")).isEqualTo(valid("000123"));
    assertThat(normalizer.normalize(" Non PSU ")).isEqualTo(valid("Non PSU"));
  }

  @Test
  void rejectsRuleNameThatIsNotRegistered() {
    assertThatThrownBy(() -> registry.normalizerFor(List.of("trim", "evaluateExpression")))
        .isInstanceOf(UnknownRuleException.class)
        .hasMessage("No normalizer named 'evaluateExpression'");
  }

  @Test
  void ruleNamesAreCaseSensitive() {
    assertThatThrownBy(() -> registry.normalizerFor(List.of("Trim")))
        .isInstanceOf(UnknownRuleException.class);
  }

  private static FieldResult<String> valid(String text) {
    return new FieldResult.Valid<>(text);
  }
}
