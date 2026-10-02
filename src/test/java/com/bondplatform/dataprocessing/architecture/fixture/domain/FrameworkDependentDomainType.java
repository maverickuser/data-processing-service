package com.bondplatform.dataprocessing.architecture.fixture.domain;

import com.bondplatform.dataprocessing.architecture.fixture.adapter.FixtureAdapter;
import org.springframework.stereotype.Component;

/** Breaks U-ARCH-01 (framework type in domain) and U-ARCH-02 (domain depends on an adapter). */
@Component
public class FrameworkDependentDomainType {

  FixtureAdapter adapter() {
    return new FixtureAdapter();
  }
}
