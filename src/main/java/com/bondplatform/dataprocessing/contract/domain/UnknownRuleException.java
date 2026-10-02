package com.bondplatform.dataprocessing.contract.domain;

/**
 * Thrown when a contract names a rule that is not part of the fixed rule set, or names a rule of
 * the wrong kind. This is a configuration error found at startup, not a problem in source data.
 */
public class UnknownRuleException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param kind what was being looked up, for example {@code normalizer}
   * @param existsAsAnotherKind whether the name is a registered rule of a different kind
   */
  public UnknownRuleException(String kind, String ruleName, boolean existsAsAnotherKind) {
    super(
        existsAsAnotherKind
            ? "Rule '" + ruleName + "' exists but is not a " + kind
            : "No " + kind + " named '" + ruleName + "'");
  }
}
