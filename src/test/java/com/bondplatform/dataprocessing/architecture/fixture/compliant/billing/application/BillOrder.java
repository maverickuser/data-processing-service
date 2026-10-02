package com.bondplatform.dataprocessing.architecture.fixture.compliant.billing.application;

import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.application.PlaceOrder;
import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.domain.Order;
import java.math.BigDecimal;
import java.time.Clock;

/** Another feature using the first feature's public application API. */
public class BillOrder {

  /** Bills an order placed through the other feature. */
  public Order bill(PlaceOrder placeOrder, BigDecimal amount, Clock clock) {
    return placeOrder.place(amount, clock);
  }
}
