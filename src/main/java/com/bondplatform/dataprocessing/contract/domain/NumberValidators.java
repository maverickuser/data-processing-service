package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.List;

/** The number validators a contract can name. */
final class NumberValidators {

  /** {@code nonNegative}: zero is allowed, anything below zero is not. */
  static final NumberValidator NON_NEGATIVE =
      value ->
          value.signum() < 0
              ? List.of(new RuleViolation(ErrorCode.NEGATIVE_VALUE, "Expected zero or more."))
              : List.of();

  private NumberValidators() {}
}
