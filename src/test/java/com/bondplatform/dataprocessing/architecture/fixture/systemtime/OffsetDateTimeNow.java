package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.OffsetDateTime;

/** Breaks U-ARCH-03. */
public class OffsetDateTimeNow {
  Object value() {
    return OffsetDateTime.now();
  }
}
