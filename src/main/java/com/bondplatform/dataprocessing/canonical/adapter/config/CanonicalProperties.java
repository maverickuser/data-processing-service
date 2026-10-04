package com.bondplatform.dataprocessing.canonical.adapter.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where canonical files are kept.
 *
 * @param bucket the service's own canonical-file bucket (LLD section 17.5)
 */
@ConfigurationProperties("data-processing.canonical")
public record CanonicalProperties(String bucket) {

  /**
   * Checks that a bucket is given.
   *
   * @throws IllegalArgumentException if it is missing
   */
  public CanonicalProperties(@Nullable String bucket) {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("data-processing.canonical.bucket is required");
    }
    this.bucket = bucket;
  }
}
