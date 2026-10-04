package com.bondplatform.dataprocessing.canonical.domain;

/** Whether a canonical field or row passed validation (LLD section 4.2). */
public enum ValidationStatus {
  /** Every applicable rule holds; a blank optional field also passes. */
  PASSED,
  /** At least one rule fails. */
  FAILED
}
