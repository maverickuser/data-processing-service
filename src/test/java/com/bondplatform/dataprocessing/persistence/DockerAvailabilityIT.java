package com.bondplatform.dataprocessing.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

/**
 * Database tests are skipped on a machine without Docker. In CI that would hide every database
 * test, so there Docker is required.
 */
class DockerAvailabilityIT {

  @Test
  @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
  void dockerIsAvailableInContinuousIntegration() {
    assertThat(DockerClientFactory.instance().isDockerAvailable()).isTrue();
  }
}
