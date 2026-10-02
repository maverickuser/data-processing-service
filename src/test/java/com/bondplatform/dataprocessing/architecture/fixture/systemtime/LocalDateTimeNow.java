package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.LocalDateTime;

/** Breaks U-ARCH-03. */
public class LocalDateTimeNow {
  Object value() {
    return LocalDateTime.now();
  }
}
