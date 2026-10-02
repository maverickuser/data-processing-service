package com.bondplatform.dataprocessing.contract.domain;

/**
 * The stable identifier of a dataset contract, carried as {@code dataschema} by a submission, for
 * example {@code urn:bond-platform:dataset:nsdl-security}.
 *
 * @param value the full URN
 */
public record DatasetUrn(String value) {

  private static final String PREFIX = "urn:bond-platform:dataset:";

  /** Rejects text that is not a bond-platform dataset URN. */
  public DatasetUrn {
    if (!value.startsWith(PREFIX) || value.length() == PREFIX.length()) {
      throw new IllegalArgumentException("Not a dataset URN: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
