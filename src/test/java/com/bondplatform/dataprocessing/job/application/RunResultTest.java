package com.bondplatform.dataprocessing.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RunResultTest {

  @Test
  void onlyRetriesAndExhaustedAttemptsKeepTheMessage() {
    assertThat(RunResult.FINISHED.acknowledgesMessage()).isTrue();
    assertThat(RunResult.ALREADY_FINISHED.acknowledgesMessage()).isTrue();
    assertThat(RunResult.UNKNOWN_JOB.acknowledgesMessage()).isTrue();
    assertThat(RunResult.retryAfter(Duration.ofMinutes(1)).acknowledgesMessage()).isFalse();
    assertThat(RunResult.ATTEMPTS_EXHAUSTED.acknowledgesMessage()).isFalse();
    assertThat(RunResult.ATTEMPTS_EXHAUSTED.retryDelay()).isZero();
  }

  @Test
  void retryDelayIsNotNegative() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RunResult.retryAfter(Duration.ofSeconds(-1)));
  }
}
