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
   * Checks that the source is set and is not a web address.
   *
   * @throws IllegalArgumentException if it is missing, blank, or an {@code http} or {@code https}
   *     URL such as a queue URL, so startup fails
   */
  public PublicationProperties(@Nullable URI source) {
    if (source == null || source.toString().isBlank()) {
      throw new IllegalArgumentException("data-processing.events.source is required");
    }
    String scheme = source.getScheme();
    if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException(
          "data-processing.events.source must be a producer identity such as a URN, not a URL");
    }
    this.source = source;
  }
}
