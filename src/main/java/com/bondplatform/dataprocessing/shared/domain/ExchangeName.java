package com.bondplatform.dataprocessing.shared.domain;

import java.util.Locale;

/**
 * The exchange a daily market summary belongs to, for example {@code BSE}.
 *
 * <p>The value is trimmed and uppercased so that the manifest input and the filename prefix compare
 * equal.
 *
 * @param value the normalized, non-blank exchange name
 */
public record ExchangeName(String value) {

  /** Rejects a value that is not already normalized; use {@link #of(String)} for raw input. */
  public ExchangeName {
    if (value.isBlank() || !value.equals(normalize(value))) {
      throw new IllegalArgumentException("Exchange name must be non-blank, trimmed, and uppercase");
    }
  }

  /**
   * Normalizes raw text into an exchange name.
   *
   * @throws IllegalArgumentException if the text is blank
   */
  public static ExchangeName of(String rawValue) {
    String normalized = normalize(rawValue);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Exchange name must not be blank");
    }
    return new ExchangeName(normalized);
  }

  @Override
  public String toString() {
    return value;
  }

  private static String normalize(String rawValue) {
    return rawValue.strip().toUpperCase(Locale.ROOT);
  }
}
