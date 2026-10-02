package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.util.UUID;

/** Breaks U-ARCH-03. */
public class RandomUuid {
  Object value() {
    return UUID.randomUUID();
  }
}
