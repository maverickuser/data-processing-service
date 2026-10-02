package com.bondplatform.dataprocessing.contract.domain;

/** Thrown when a contract file is missing something or holds a value of the wrong shape. */
public class ContractFormatException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param location where in the contract the problem is, for example {@code fields.isin.type}
   */
  public ContractFormatException(String location, String problem) {
    super("Invalid contract at '" + location + "': " + problem);
  }
}
