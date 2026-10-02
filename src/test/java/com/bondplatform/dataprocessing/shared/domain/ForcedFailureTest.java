package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Deliberately failing test used once to prove CI uploads reports on failure. Never merged. */
class ForcedFailureTest {

  @Test
  void failsOnPurpose() {
    assertThat(1).isEqualTo(2);
  }
}
