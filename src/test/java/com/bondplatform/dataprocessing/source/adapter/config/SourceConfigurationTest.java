package com.bondplatform.dataprocessing.source.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class SourceConfigurationTest {

  private final SourceConfiguration configuration = new SourceConfiguration();

  @Test
  void buildsClientForTheRegionWithTimeoutsAndPathStyleOnlyForTestEndpoints() {
    try (S3Client aws =
            configuration.s3Client(new SourceProperties("ap-south-1", null, List.of("b")));
        S3Client local =
            configuration.s3Client(
                new SourceProperties(
                    "ap-south-1", URI.create("http://127.0.0.1:9090"), List.of("b")))) {
      assertThat(aws.serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTH_1);
      assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
      assertThat(aws.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
          .contains(SourceConfiguration.API_CALL_TIMEOUT);
      assertThat(local.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("http://127.0.0.1:9090"));
    }
  }

  @Test
  void storeReadsOnlyFromTheConfiguredBuckets() {
    try (S3Client s3 =
        configuration.s3Client(new SourceProperties("ap-south-1", null, List.of("b")))) {
      assertThat(
              configuration.s3SourceStore(
                  s3, new SourceProperties("ap-south-1", null, List.of("artifacts"))))
          .isNotNull();
    }
  }

  @Test
  void regionAndAtLeastOneBucketAreRequired() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SourceProperties(" ", null, List.of("b")))
        .withMessageContaining("region");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SourceProperties("ap-south-1", null, List.of()))
        .withMessageContaining("allowed-buckets");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SourceProperties("ap-south-1", null, null))
        .withMessageContaining("allowed-buckets");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SourceProperties("ap-south-1", null, List.of(" ")))
        .withMessageContaining("allowed-buckets");
  }
}
