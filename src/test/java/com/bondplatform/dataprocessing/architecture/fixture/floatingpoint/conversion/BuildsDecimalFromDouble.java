package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

import java.math.BigDecimal;

/** Breaks U-ARCH-03. */
public class BuildsDecimalFromDouble {
  BigDecimal tenth() {
    return new BigDecimal(0.1);
  }
}
