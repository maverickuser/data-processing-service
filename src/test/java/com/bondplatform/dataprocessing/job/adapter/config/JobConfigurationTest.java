package com.bondplatform.dataprocessing.job.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobConfigurationTest {

  @Test
  void usesTheAgreedDelaysUnlessOthersAreConfigured() {
    JobConfiguration configuration = new JobConfiguration();

    assertThat(configuration.retryPolicy(new WorkerProperties(null)))
        .isEqualTo(RetryPolicy.STANDARD);
    assertThat(configuration.retryPolicy(new WorkerProperties(List.of())))
        .isEqualTo(RetryPolicy.STANDARD);
    assertThat(
            configuration
                .retryPolicy(
                    new WorkerProperties(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2))))
                .delayAfterFailedAttempt(2))
        .contains(Duration.ofSeconds(2));
  }

  // Review finding X-3: the number of delays sets the number of attempts, which is fixed.
  @Test
  void changingTheNumberOfDelaysIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkerProperties(List.of(Duration.ofSeconds(1))))
        .withMessageContaining("exactly 2");
  }
}
