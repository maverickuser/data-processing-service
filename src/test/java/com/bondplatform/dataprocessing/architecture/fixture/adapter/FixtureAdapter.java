package com.bondplatform.dataprocessing.architecture.fixture.adapter;

import com.bondplatform.dataprocessing.architecture.fixture.application.CyclicUseCase;

/** An adapter that is wrongly used from domain code, and closes a package cycle. */
public class FixtureAdapter {

  public CyclicUseCase useCase() {
    return new CyclicUseCase();
  }
}
