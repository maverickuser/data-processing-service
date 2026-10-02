package com.bondplatform.dataprocessing.architecture.fixture.outerlayer.domain;

import com.bondplatform.dataprocessing.architecture.fixture.outerlayer.adapter.SomeAdapter;

/** Breaks U-ARCH-02: domain code depends on an adapter. */
public class DomainUsingAdapter {
  SomeAdapter adapter = new SomeAdapter();
}
