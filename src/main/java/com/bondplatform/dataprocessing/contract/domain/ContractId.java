package com.bondplatform.dataprocessing.contract.domain;

/**
 * Identifies one immutable version of a contract.
 *
 * @param id the contract family, for example {@code nsdl-security-json}
 * @param version the version label, for example {@code v1}
 */
public record ContractId(String id, String version) {

  /** Rejects blank parts. */
  public ContractId {
    if (id.isBlank() || version.isBlank()) {
      throw new IllegalArgumentException("Contract id and version must not be blank");
    }
  }

  /** Returns the name other contracts and files use, for example {@code nsdl-security-json-v1}. */
  public String name() {
    return id + "-" + version;
  }

  @Override
  public String toString() {
    return name();
  }
}
