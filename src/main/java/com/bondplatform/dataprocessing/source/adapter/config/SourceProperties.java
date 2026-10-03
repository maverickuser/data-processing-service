package com.bondplatform.dataprocessing.source.adapter.config;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where source objects are read from.
 *
 * @param region the AWS region of the buckets
 * @param endpoint an S3 endpoint to use instead of AWS's, for local testing only; null in AWS
 * @param allowedBuckets the only buckets the service reads manifests and files from
 */
@ConfigurationProperties("data-processing.sources")
public record SourceProperties(String region, @Nullable URI endpoint, List<String> allowedBuckets) {

  /**
   * Checks that a region and at least one bucket are given.
   *
   * @throws IllegalArgumentException if either is missing
   */
  public SourceProperties(
      @Nullable String region, @Nullable URI endpoint, @Nullable List<String> allowedBuckets) {
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("data-processing.sources.region is required");
    }
    if (allowedBuckets == null
        || allowedBuckets.isEmpty()
        || allowedBuckets.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(
          "data-processing.sources.allowed-buckets needs at least one bucket name");
    }
    this.region = region;
    this.endpoint = endpoint;
    this.allowedBuckets = List.copyOf(allowedBuckets);
  }
}
