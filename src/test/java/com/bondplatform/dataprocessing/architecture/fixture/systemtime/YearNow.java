package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.Year;

/** Breaks U-ARCH-03. */
public class YearNow {
  Object value() {
    return Year.now();
  }
}
