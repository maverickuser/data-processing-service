package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.time.InstantSource;

/** Breaks U-ARCH-03. */
public class SystemInstantSource {
  Object value() {
    return InstantSource.system();
  }
}
