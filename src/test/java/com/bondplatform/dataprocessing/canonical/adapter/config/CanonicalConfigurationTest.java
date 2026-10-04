package com.bondplatform.dataprocessing.canonical.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;

class CanonicalConfigurationTest {

  @Test
  void storeWritesToTheConfiguredBucket() {
    assertThat(
            new CanonicalConfiguration()
                .s3CanonicalFileStore(mock(S3Client.class), new CanonicalProperties("canonical")))
        .isNotNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void bucketIsRequired(String bucket) {
    assertThatThrownBy(() -> new CanonicalProperties(bucket))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("data-processing.canonical.bucket is required");
  }
}
