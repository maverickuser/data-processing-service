package com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/** Plain domain value with exact numbers and an injected clock. */
public record Order(BigDecimal amount, Instant placedAt) {

  /** Creates an order placed now, according to the given clock. */
  public static Order placedNow(BigDecimal amount, Clock clock) {
    return new Order(amount, Instant.now(clock));
  }
}
