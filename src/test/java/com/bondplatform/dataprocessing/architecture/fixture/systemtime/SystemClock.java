package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.Clock;
import java.time.Instant;

/** Breaks U-ARCH-03. */
public class SystemClock {
  Object value() {
    return Instant.now(Clock.systemUTC());
  }
}
