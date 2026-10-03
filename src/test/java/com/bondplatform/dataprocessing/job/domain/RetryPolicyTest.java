package com.bondplatform.dataprocessing.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test case U-JOB-01, the policy's half. */
class RetryPolicyTest {

  @Test
  void agreedPolicyRetriesAfterOneThenFiveMinutesAndStopsAfterThreeAttempts() {
    RetryPolicy policy = RetryPolicy.STANDARD;

    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.delayAfterFailedAttempt(1)).contains(Duration.ofMinutes(1));
    assertThat(policy.delayAfterFailedAttempt(2)).contains(Duration.ofMinutes(5));
    assertThat(policy.delayAfterFailedAttempt(3)).isEmpty();
    assertThat(policy.delayAfterFailedAttempt(4)).isEmpty();
  }

  @Test
  void policyWithoutDelaysAllowsOneAttempt() {
    RetryPolicy once = new RetryPolicy(List.of());

    assertThat(once.maxAttempts()).isEqualTo(1);
    assertThat(once.delayAfterFailedAttempt(1)).isEmpty();
  }

  @Test
  void attemptNumbersStartAtOneAndDelaysAreNotNegative() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RetryPolicy.STANDARD.delayAfterFailedAttempt(0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RetryPolicy(List.of(Duration.ofSeconds(-1))));
  }
}
