package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.Instant;

/** Breaks U-ARCH-03. */
public class InstantNow {
  Object value() {
    return Instant.now();
  }
}
