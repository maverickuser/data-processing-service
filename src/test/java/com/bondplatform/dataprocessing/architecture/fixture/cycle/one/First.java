package com.bondplatform.dataprocessing.architecture.fixture.cycle.one;

import com.bondplatform.dataprocessing.architecture.fixture.cycle.two.Second;

/** Breaks U-ARCH-02: one half of a package cycle. */
public class First {
  Second second = new Second();
}
