package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class IsinPageTest {

  @Test
  void pageHoldsAtMostFifty() {
    assertThat(new IsinPage(Collections.nCopies(50, "INE0KH208019"), "next").items()).hasSize(50);
    List<String> tooMany = Collections.nCopies(51, "INE0KH208019");
    assertThatThrownBy(() -> new IsinPage(tooMany, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("51 ISINs do not fit on one page");
  }
}
