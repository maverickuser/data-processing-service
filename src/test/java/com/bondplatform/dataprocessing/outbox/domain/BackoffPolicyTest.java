package com.bondplatform.dataprocessing.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Test case U-OBX-02, the backoff half. */
class BackoffPolicyTest {

  @Test
  void delayDoublesFromTenSecondsUpToFiveMinutes() {
    assertThat(BackoffPolicy.delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(BackoffPolicy.delayAfter(2)).isEqualTo(Duration.ofSeconds(20));
    assertThat(BackoffPolicy.delayAfter(3)).isEqualTo(Duration.ofSeconds(40));
    assertThat(BackoffPolicy.delayAfter(4)).isEqualTo(Duration.ofSeconds(80));
    assertThat(BackoffPolicy.delayAfter(5)).isEqualTo(Duration.ofSeconds(160));
    assertThat(BackoffPolicy.delayAfter(6)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void delayStaysAtTheCapHoweverManyAttemptsFailed() {
    assertThat(BackoffPolicy.delayAfter(64)).isEqualTo(BackoffPolicy.MAX_DELAY);
    assertThat(BackoffPolicy.delayAfter(Integer.MAX_VALUE)).isEqualTo(BackoffPolicy.MAX_DELAY);
  }

  @Test
  void delayNeedsFailedAttempt() {
    assertThatIllegalArgumentException().isThrownBy(() -> BackoffPolicy.delayAfter(0));
  }
}
