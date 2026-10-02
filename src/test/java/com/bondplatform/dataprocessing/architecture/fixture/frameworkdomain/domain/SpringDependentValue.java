package com.bondplatform.dataprocessing.architecture.fixture.frameworkdomain.domain;

import org.springframework.util.StringUtils;

/** Breaks U-ARCH-01: a framework type in domain code. */
public class SpringDependentValue {

  boolean hasText(String value) {
    return StringUtils.hasText(value);
  }
}
