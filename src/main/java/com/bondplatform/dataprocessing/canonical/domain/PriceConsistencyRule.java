package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceContract.Comparison;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.RowRule;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * One cross-field price comparison from the source contract, such as {@code high_price >=
 * low_price} (LLD section 5.3).
 *
 * <p>The comparison runs only when both fields are populated and individually valid, so a field
 * that is blank, unparseable, or already failed its own rules never adds a dependent error.
 */
public final class PriceConsistencyRule {

  private final RowRule rule;

  /** Creates the check for one contract row rule. */
  public PriceConsistencyRule(RowRule rule) {
    this.rule = rule;
  }

  /** Returns the problem when both fields are valid numbers that break the comparison. */
  public Optional<ValidationIssue> check(Map<String, CanonicalField> fields) {
    Optional<BigDecimal> left = validNumber(fields, rule.left());
    Optional<BigDecimal> right = validNumber(fields, rule.right());
    if (left.isEmpty() || right.isEmpty() || holds(left.get(), right.get())) {
      return Optional.empty();
    }
    return Optional.of(
        new ValidationIssue(
            ErrorCode.PRICE_INCONSISTENT,
            rule.left() + " must be " + relation() + " " + rule.right() + "."));
  }

  private boolean holds(BigDecimal left, BigDecimal right) {
    int order = left.compareTo(right);
    return rule.comparison() == Comparison.GREATER_THAN_OR_EQUAL ? order >= 0 : order <= 0;
  }

  private String relation() {
    return rule.comparison() == Comparison.GREATER_THAN_OR_EQUAL
        ? "greater than or equal to"
        : "less than or equal to";
  }

  private static Optional<BigDecimal> validNumber(Map<String, CanonicalField> fields, String name) {
    return Optional.ofNullable(fields.get(name)).flatMap(CanonicalField::validNumber);
  }
}
