package com.bondplatform.dataprocessing.architecture.fixture.outerlayer.application;

import com.bondplatform.dataprocessing.architecture.fixture.outerlayer.adapter.SomeAdapter;

/** Breaks U-ARCH-02: application code depends on an adapter. */
public class UseCaseUsingAdapter {
  SomeAdapter adapter = new SomeAdapter();
}
