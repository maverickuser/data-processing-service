package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.LocalDate;

/** Breaks U-ARCH-03. */
public class LocalDateNow {
  Object value() {
    return LocalDate.now();
  }
}
