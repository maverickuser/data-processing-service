package com.bondplatform.dataprocessing.architecture.fixture.cycle.two;

import com.bondplatform.dataprocessing.architecture.fixture.cycle.one.First;

/** Breaks U-ARCH-02: the other half of a package cycle. */
public class Second {
  public First first() {
    return new First();
  }
}
