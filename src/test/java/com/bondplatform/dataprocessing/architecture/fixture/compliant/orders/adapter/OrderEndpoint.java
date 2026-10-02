package com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.adapter;

import com.bondplatform.dataprocessing.architecture.fixture.compliant.orders.application.PlaceOrder;
import com.bondplatform.dataprocessing.architecture.fixture.compliant.shared.adapter.web.SharedErrorShape;

/** Adapter depending on its own application layer. */
public class OrderEndpoint {
  private final PlaceOrder placeOrder = new PlaceOrder();
  private final SharedErrorShape errorShape = new SharedErrorShape();

  /** Exposes the shared error shape this endpoint uses. */
  public SharedErrorShape errorShape() {
    return errorShape;
  }

  /** Exposes the use case. */
  public PlaceOrder placeOrder() {
    return placeOrder;
  }
}
