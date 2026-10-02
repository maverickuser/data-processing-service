package com.bondplatform.dataprocessing.shared.domain;

/**
 * A security identifier, normalized so that two spellings of the same ISIN are equal.
 *
 * <p>The value is trimmed and uppercased. No length, format, or check-digit validation is applied,
 * by design (LLD section 5.2).
 *
 * @param value the normalized, non-blank identifier
 */
public record Isin(String value) {

  /** Rejects a value that is not already normalized; use {@link #of(String)} for raw input. */
  public Isin {
    if (value.isBlank() || !value.equals(NormalizedText.trimmedUppercase(value))) {
      throw new IllegalArgumentException("ISIN must be non-blank, trimmed, and uppercase");
    }
  }

  /**
   * Normalizes raw text into an ISIN.
   *
   * @throws IllegalArgumentException if the text is blank
   */
  public static Isin of(String rawValue) {
    String normalized = NormalizedText.trimmedUppercase(rawValue);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("ISIN must not be blank");
    }
    return new Isin(normalized);
  }

  @Override
  public String toString() {
    return value;
  }
}
