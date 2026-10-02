package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.util.UUID;
import java.util.function.Supplier;

/** Breaks U-ARCH-03. */
public class RandomUuidReference {
  Object value() {
    return (Supplier<UUID>) UUID::randomUUID;
  }
}
