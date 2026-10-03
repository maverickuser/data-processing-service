package com.bondplatform.dataprocessing.source.adapter.config;

import com.bondplatform.dataprocessing.source.adapter.aws.S3SourceStore;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** Wires reading of manifests and source files from S3. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourceProperties.class)
public class SourceConfiguration {

  /** Gives up on one read, retries included, after this long: 10 MiB takes seconds, not minutes. */
  static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(60);

  static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(20);

  /**
   * Returns the S3 client. It resolves credentials on first use, not at startup, and has an
   * explicit timeout on every call. A test endpoint is addressed by path, as emulators expect.
   */
  @Bean(destroyMethod = "close")
  public S3Client s3Client(SourceProperties sources) {
    S3ClientBuilder builder =
        S3Client.builder()
            .region(Region.of(sources.region()))
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(ATTEMPT_TIMEOUT))
            .overrideConfiguration(
                config ->
                    config.apiCallTimeout(API_CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT));
    if (sources.endpoint() != null) {
      builder.endpointOverride(sources.endpoint()).forcePathStyle(true);
    }
    return builder.build();
  }

  /** Returns the store reading manifests and listed files from the allowed buckets. */
  @Bean
  public S3SourceStore s3SourceStore(S3Client s3, SourceProperties sources) {
    return new S3SourceStore(s3, Set.copyOf(sources.allowedBuckets()));
  }
}
