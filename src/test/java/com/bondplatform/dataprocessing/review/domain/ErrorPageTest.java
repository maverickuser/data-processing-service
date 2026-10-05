package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ErrorPageTest {

  @Test
  void pageHoldsAtMostFifty() {
    assertThat(new ErrorPage(JobStatusViewTest.errors(50), "next").items()).hasSize(50);
    assertThatThrownBy(() -> new ErrorPage(JobStatusViewTest.errors(51), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
