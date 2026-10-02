package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** Breaks U-ARCH-03. */
public class LocalDateNowInZone {
  Object value() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
