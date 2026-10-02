package com.bondplatform.dataprocessing.contract.domain;

import java.util.Arrays;
import java.util.Locale;

/** The value types a contract can give a field. */
public enum FieldType {
  /** Text, kept as written apart from the named normalizations. */
  TEXT,
  /** An exact decimal number. */
  DECIMAL,
  /** A mathematical whole number. */
  INTEGER,
  /** A calendar date. */
  DATE,
  /** An exact number of percentage points. */
  PERCENT;

  /**
   * Returns the type a contract names, for example {@code decimal}.
   *
   * @throws IllegalArgumentException if no type has that name
   */
  public static FieldType fromContractName(String name) {
    return Arrays.stream(values())
        .filter(type -> type.contractName().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown field type: " + name));
  }

  /** Returns the lowercase name used in contract files. */
  public String contractName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
