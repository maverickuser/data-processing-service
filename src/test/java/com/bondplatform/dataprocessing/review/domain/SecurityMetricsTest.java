package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Test case U-REV-10. */
class SecurityMetricsTest {

  @Test
  void countsBetweenZeroAndTheWholeAreAccepted() {
    assertThat(new SecurityMetrics(5, 0, 5, 3, 2, 1).withoutCurrentRatings()).isEqualTo(3);
    assertThat(new SecurityMetrics(0, 0, 0, 0, 0, 0).securities()).isZero();
  }

  @Test
  void negativeCountOrPartLargerThanTheWholeIsRefused() {
    assertThatThrownBy(() -> new SecurityMetrics(5, -1, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecurityMetrics(5, 0, 0, 0, 0, 6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Each count must be between 0 and the 5 securities: 6");
  }
}
