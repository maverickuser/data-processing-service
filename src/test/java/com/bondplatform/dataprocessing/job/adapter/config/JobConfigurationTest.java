package com.bondplatform.dataprocessing.job.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

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
                .retryPolicy(new WorkerProperties(List.of(Duration.ofSeconds(1))))
                .maxAttempts())
        .isEqualTo(2);
  }
}
