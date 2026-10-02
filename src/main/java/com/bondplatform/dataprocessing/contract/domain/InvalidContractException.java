package com.bondplatform.dataprocessing.contract.domain;

import java.util.List;

/** Thrown at startup when a contract is internally inconsistent; lists every problem found. */
public class InvalidContractException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> problems;

  /** Creates the exception for the named contract and its problems. */
  public InvalidContractException(String contractName, List<String> problems) {
    super("Contract " + contractName + " is invalid: " + String.join("; ", problems));
    this.problems = List.copyOf(problems);
  }

  /** Returns every problem found, one sentence each. */
  public List<String> problems() {
    return problems;
  }
}
