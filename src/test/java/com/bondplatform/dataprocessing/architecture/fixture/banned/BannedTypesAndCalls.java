package com.bondplatform.dataprocessing.architecture.fixture.banned;

import java.time.Instant;
import java.util.UUID;

/** Breaks U-ARCH-03: floating point, system time, and random identifiers. */
public class BannedTypesAndCalls {

  double price;

  float half(float value) {
    return value / 2;
  }

  String stamp() {
    return Instant.now() + "-" + UUID.randomUUID();
  }
}
