package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

import java.math.BigDecimal;

/** Breaks U-ARCH-03. */
public class ConvertsDoubleToDecimal {
  BigDecimal tenth() {
    return BigDecimal.valueOf(0.1);
  }
}
