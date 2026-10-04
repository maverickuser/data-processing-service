package com.bondplatform.dataprocessing.publication.adapter.config;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Publication settings, under {@code data-processing.events}.
 *
 * @param source the CloudEvent {@code source} of every event this service produces: a stable
 *     producer identity that includes the environment, never a queue or download URL (LLD section
 *     14.2)
 */
@ConfigurationProperties("data-processing.events")
public record PublicationProperties(URI source) {

  /**
   * Checks that the source is set.
   *
   * @throws IllegalArgumentException if it is missing or blank, so startup fails
   */
  public PublicationProperties(@Nullable URI source) {
    if (source == null || source.toString().isBlank()) {
      throw new IllegalArgumentException("data-processing.events.source is required");
    }
    this.source = source;
  }
}
