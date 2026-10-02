package com.bondplatform.dataprocessing.shared.domain;

import java.util.UUID;

/**
 * Identifies one accepted submission; the {@code jobId} of the public API.
 *
 * @param value the identifier
 */
public record JobId(UUID value) {

  /**
   * Parses the textual form used in URLs and responses.
   *
   * @throws IllegalArgumentException if the text is not a UUID
   */
  public static JobId parse(String text) {
    try {
      return new JobId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Job ID must be a UUID: " + text, e);
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
