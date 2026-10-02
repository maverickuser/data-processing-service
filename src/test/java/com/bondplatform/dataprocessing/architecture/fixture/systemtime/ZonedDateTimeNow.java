package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.ZonedDateTime;

/** Breaks U-ARCH-03. */
public class ZonedDateTimeNow {
  Object value() {
    return ZonedDateTime.now();
  }
}
