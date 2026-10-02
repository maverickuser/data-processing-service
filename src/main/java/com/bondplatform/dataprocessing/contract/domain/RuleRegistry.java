package com.bondplatform.dataprocessing.contract.domain;

import java.util.List;
import java.util.Map;

/**
 * The closed set of rules that contracts refer to by name.
 *
 * <p>Contracts are data: they can only name rules registered here, never supply code. Adding a rule
 * means adding it to this registry with a test.
 */
public final class RuleRegistry {

  private static final Normalizer IDENTITY = FieldResult.Valid::new;

  private final Map<String, Normalizer> normalizers;
  private final Map<String, NumberValidator> numberValidators;

  private RuleRegistry(
      Map<String, Normalizer> normalizers, Map<String, NumberValidator> numberValidators) {
    this.normalizers = Map.copyOf(normalizers);
    this.numberValidators = Map.copyOf(numberValidators);
  }

  /** Returns the registry holding every rule this service implements. */
  public static RuleRegistry standard() {
    return new RuleRegistry(
        Map.of(
            "trim", TextNormalizers.TRIM,
            "blankToNull", TextNormalizers.BLANK_TO_NULL,
            "uppercase", TextNormalizers.UPPERCASE,
            "normalizeGroupedNumber", NumberNormalizers.GROUPED_NUMBER,
            "stripTrailingPercent", NumberNormalizers.STRIP_TRAILING_PERCENT),
        Map.of("nonNegative", NumberValidators.NON_NEGATIVE));
  }

  /**
   * Returns one normalizer that applies the named steps in order.
   *
   * @throws UnknownRuleException if any name is not a registered normalizer
   */
  public Normalizer normalizerFor(List<String> ruleNames) {
    Normalizer chain = IDENTITY;
    for (String ruleName : ruleNames) {
      chain = chain.then(normalizer(ruleName));
    }
    return chain;
  }

  /**
   * Returns one validator that applies every named rule and reports every violation, not only the
   * first.
   *
   * @throws UnknownRuleException if any name is not a registered number validator
   */
  public NumberValidator numberValidatorFor(List<String> ruleNames) {
    List<NumberValidator> validators = ruleNames.stream().map(this::numberValidator).toList();
    return value ->
        validators.stream().flatMap(validator -> validator.validate(value).stream()).toList();
  }

  private NumberValidator numberValidator(String ruleName) {
    NumberValidator validator = numberValidators.get(ruleName);
    if (validator == null) {
      throw new UnknownRuleException(
          "number validator", ruleName, normalizers.containsKey(ruleName));
    }
    return validator;
  }

  private Normalizer normalizer(String ruleName) {
    Normalizer normalizer = normalizers.get(ruleName);
    if (normalizer == null) {
      throw new UnknownRuleException(
          "normalizer", ruleName, numberValidators.containsKey(ruleName));
    }
    return normalizer;
  }
}
