package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.Instant;
import java.util.function.Supplier;

/** Breaks U-ARCH-03. */
public class InstantNowReference {
  Object value() {
    return (Supplier<Instant>) Instant::now;
  }
}
