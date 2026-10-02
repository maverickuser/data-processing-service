package com.bondplatform.dataprocessing.shared.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifies one accepted submission; the {@code jobId} of the public API.
 *
 * @param value the identifier
 */
public record JobId(UUID value) {

  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

  /** Rejects a missing identifier. */
  public JobId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Parses the textual form used in URLs and responses.
   *
   * <p>Only the canonical 36-character form is accepted. Shortened forms that {@link
   * UUID#fromString} would pad, such as {@code 1-1-1-1-1}, are rejected so that two different texts
   * never name the same job.
   *
   * @throws IllegalArgumentException if the text is not a canonical UUID
   */
  public static JobId parse(String text) {
    if (!CANONICAL_UUID.matcher(text).matches()) {
      throw new IllegalArgumentException("Job ID must be a canonical UUID: " + text);
    }
    return new JobId(UUID.fromString(text));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
