package com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.application;

import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.domain.Order;
import java.math.BigDecimal;
import java.time.Clock;

/** Use case depending only on its own domain. */
public class PlaceOrder {

  /** Places an order. */
  public Order place(BigDecimal amount, Clock clock) {
    return Order.placedNow(amount, clock);
  }
}
