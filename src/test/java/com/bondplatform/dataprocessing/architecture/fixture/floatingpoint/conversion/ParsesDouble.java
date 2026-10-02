package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

/** Breaks U-ARCH-03. */
public class ParsesDouble {
  boolean isPositive(String text) {
    return Double.parseDouble(text) > 0;
  }
}
