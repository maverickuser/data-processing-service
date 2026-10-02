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

  private RuleRegistry(Map<String, Normalizer> normalizers) {
    this.normalizers = Map.copyOf(normalizers);
  }

  /** Returns the registry holding every rule this service implements. */
  public static RuleRegistry standard() {
    return new RuleRegistry(
        Map.of(
            "trim", TextNormalizers.TRIM,
            "blankToNull", TextNormalizers.BLANK_TO_NULL,
            "uppercase", TextNormalizers.UPPERCASE));
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

  private Normalizer normalizer(String ruleName) {
    Normalizer normalizer = normalizers.get(ruleName);
    if (normalizer == null) {
      throw new UnknownRuleException(ruleName);
    }
    return normalizer;
  }
}
