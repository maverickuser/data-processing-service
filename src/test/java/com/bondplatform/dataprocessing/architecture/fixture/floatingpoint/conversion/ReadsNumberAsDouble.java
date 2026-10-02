package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

/** Breaks U-ARCH-03. */
public class ReadsNumberAsDouble {
  boolean isPositive(Number value) {
    return value.doubleValue() > 0;
  }
}
