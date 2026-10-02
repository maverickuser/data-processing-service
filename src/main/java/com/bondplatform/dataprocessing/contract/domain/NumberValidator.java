package com.bondplatform.dataprocessing.contract.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * A constraint on a successfully parsed number, named in a contract, such as {@code nonNegative}.
 *
 * <p>Validation runs only after parsing succeeds, so a violation always comes with the parsed
 * number, which distinguishes it from text that could not be parsed at all.
 */
@FunctionalInterface
public interface NumberValidator {

  /** Returns every rule the value breaks; empty when it is valid. */
  List<RuleViolation> validate(BigDecimal value);
}
