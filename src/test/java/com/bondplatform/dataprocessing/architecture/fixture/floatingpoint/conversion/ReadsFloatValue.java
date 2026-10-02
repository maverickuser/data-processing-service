package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

import java.math.BigDecimal;

/** Breaks U-ARCH-03. */
public class ReadsFloatValue {
  boolean isPositive(BigDecimal value) {
    return value.floatValue() > 0;
  }
}
