package com.bondplatform.dataprocessing.architecture.fixture.compliant.lambda;

import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.adapter.OrderEndpoint;

/** An entry point wiring a feature's adapter. */
public class EntryPoint {
  private final OrderEndpoint endpoint = new OrderEndpoint();

  /** Exposes the wired adapter. */
  public OrderEndpoint endpoint() {
    return endpoint;
  }
}
