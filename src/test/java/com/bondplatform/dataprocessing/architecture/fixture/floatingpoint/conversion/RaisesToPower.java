package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

/** Breaks U-ARCH-03. */
public class RaisesToPower {
  boolean isLarge(int base) {
    return Math.pow(base, 2) > 100;
  }
}
