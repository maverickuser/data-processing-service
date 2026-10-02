package com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.adapter;

import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.application.PlaceOrder;

/** Adapter depending on its own application layer. */
public class OrderEndpoint {
  private final PlaceOrder placeOrder = new PlaceOrder();

  /** Exposes the use case. */
  public PlaceOrder placeOrder() {
    return placeOrder;
  }
}
