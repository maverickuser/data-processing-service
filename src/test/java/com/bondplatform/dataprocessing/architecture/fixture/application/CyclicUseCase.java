package com.bondplatform.dataprocessing.architecture.fixture.application;

import com.bondplatform.dataprocessing.architecture.fixture.domain.FrameworkDependentDomainType;

/** Breaks U-ARCH-02: completes the cycle application -> domain -> adapter -> application. */
public class CyclicUseCase {

  public FrameworkDependentDomainType domainType() {
    return new FrameworkDependentDomainType();
  }
}
