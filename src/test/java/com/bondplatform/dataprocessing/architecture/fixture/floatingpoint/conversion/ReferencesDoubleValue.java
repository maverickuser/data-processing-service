package com.bondplatform.dataprocessing.architecture.fixture.floatingpoint.conversion;

import java.math.BigDecimal;
import java.util.function.ToDoubleFunction;

/** Breaks U-ARCH-03. */
public class ReferencesDoubleValue {
  Object converter() {
    return (ToDoubleFunction<BigDecimal>) BigDecimal::doubleValue;
  }
}
