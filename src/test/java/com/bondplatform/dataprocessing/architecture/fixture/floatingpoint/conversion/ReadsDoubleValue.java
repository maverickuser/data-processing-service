package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

import java.math.BigDecimal;

/** Breaks U-ARCH-03. */
public class ReadsDoubleValue {
  boolean isPositive(BigDecimal value) {
    return value.doubleValue() > 0;
  }
}
