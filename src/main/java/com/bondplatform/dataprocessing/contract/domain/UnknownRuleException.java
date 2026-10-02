package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/** Thrown when a contract names a rule that is not part of the fixed rule set. */
public class UnknownRuleException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception for the given rule name. */
  public UnknownRuleException(String ruleName) {
    super(ErrorCode.UNKNOWN_RULE + ": no rule named '" + ruleName + "'");
  }
}
