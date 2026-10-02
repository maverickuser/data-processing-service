package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

/** Breaks U-ARCH-03. */
public class CurrentTimeMillis {
  Object value() {
    return System.currentTimeMillis();
  }
}
