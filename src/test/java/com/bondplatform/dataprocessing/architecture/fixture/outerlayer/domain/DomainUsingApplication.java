package com.bondplatform.dataprocessing.architecture.fixture.outerlayer.domain;

import com.bondplatform.dataprocessing.architecture.fixture.outerlayer.application.SomeUseCase;

/** Breaks U-ARCH-02: domain code depends on application code. */
public class DomainUsingApplication {
  SomeUseCase useCase = new SomeUseCase();
}
