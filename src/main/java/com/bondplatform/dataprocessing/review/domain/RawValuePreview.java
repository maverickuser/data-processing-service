package com.bondplatform.dataprocessing.review.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * An error's source value as the API shows it (LLD section 16): text keeps its JSON type, cut to
 * the first {@value #MAX_CHARACTERS} Unicode characters without splitting one; an object or array
 * is shown as its JSON text, cut the same way. The full value stays in stored evidence.
 *
 * @param value a {@link String}, {@link BigDecimal} or {@link Boolean}, or {@code null} when the
 *     error has no source value, which the API then omits
 * @param truncated whether the value was cut
 */
public record RawValuePreview(@Nullable Object value, boolean truncated) {

  /** The most Unicode characters shown. */
  public static final int MAX_CHARACTERS = 1_000;

  private static final RawValuePreview NONE = new RawValuePreview(null, false);

  /**
   * Returns the preview of a stored raw value.
   *
   * @param type the stored value's JSON type: {@code string}, {@code number}, {@code boolean},
   *     {@code null}, {@code object} or {@code array}; {@code null} when nothing was stored
   * @param text the value as text: a string's content, a number or boolean as written, or an
   *     object's or array's JSON text
   * @throws IllegalArgumentException if the type is unknown or the text does not fit it
   */
  public static RawValuePreview of(@Nullable String type, @Nullable String text) {
    if (type == null || type.equals("null")) {
      // A JSON null never yields an issue, since it means no update (LLD section 13.4).
      return NONE;
    }
    if (text == null) {
      throw new IllegalArgumentException("A stored " + type + " value has no text");
    }
    return switch (type) {
      case "string", "object", "array" -> cut(text);
      case "number" -> new RawValuePreview(new BigDecimal(text), false);
      case "boolean" -> new RawValuePreview(Boolean.parseBoolean(text), false);
      default -> throw new IllegalArgumentException("Unknown JSON type " + type);
    };
  }

  /** Returns no value, for an error without a source value. */
  public static RawValuePreview none() {
    return NONE;
  }

  private static RawValuePreview cut(String text) {
    if (text.codePointCount(0, text.length()) <= MAX_CHARACTERS) {
      return new RawValuePreview(text, false);
    }
    return new RawValuePreview(text.substring(0, text.offsetByCodePoints(0, MAX_CHARACTERS)), true);
  }
}
