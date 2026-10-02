package com.bondplatform.dataprocessing.shared.adapter.web;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where callers reach this service.
 *
 * @param publicBaseUrl the absolute origin of the public API, for example {@code
 *     https://processing.kagent.app}; links in responses are built from it
 */
@ConfigurationProperties("data-processing.api")
public record PublicApiProperties(URI publicBaseUrl) {

  /**
   * Checks the origin and removes a trailing slash.
   *
   * @throws IllegalArgumentException if the origin is missing or not an absolute http(s) URL
   */
  public PublicApiProperties(@Nullable URI publicBaseUrl) {
    if (publicBaseUrl == null
        || publicBaseUrl.getHost() == null
        || !("https".equals(publicBaseUrl.getScheme())
            || "http".equals(publicBaseUrl.getScheme()))) {
      throw new IllegalArgumentException(
          "data-processing.api.public-base-url must be an absolute http or https URL");
    }
    String text = publicBaseUrl.toString();
    this.publicBaseUrl =
        text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : publicBaseUrl;
  }

  /** Returns the absolute URL of a path, which must start with a slash. */
  public URI urlOf(String path) {
    return URI.create(publicBaseUrl + path);
  }
}
