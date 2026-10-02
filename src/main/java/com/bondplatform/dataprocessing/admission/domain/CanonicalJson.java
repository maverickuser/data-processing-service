package com.bondplatform.dataprocessing.admission.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Writes parsed JSON in one fixed form: object keys in sorted order, no insignificant whitespace,
 * arrays in their own order, and every scalar as it was parsed.
 *
 * <p>Two submissions are the same event exactly when their canonical forms are equal, whatever the
 * whitespace and key order of the bodies that were sent. That is what tells a replay from a
 * conflicting reuse of an idempotency key.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /**
   * Returns the canonical text of a parsed JSON value.
   *
   * @param value a map, list, string, number, boolean, or null
   * @throws IllegalArgumentException if the value contains anything else
   */
  public static String of(@Nullable Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String text) {
      return quote(text);
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
      return sorted.entrySet().stream()
          .map(entry -> quote(entry.getKey()) + ":" + of(entry.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream().map(CanonicalJson::of).collect(Collectors.joining(",", "[", "]"));
    }
    throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
  }

  private static String quote(String text) {
    StringBuilder quoted = new StringBuilder("\"");
    text.chars()
        .forEach(
            character -> {
              switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                  if (character < 0x20) {
                    quoted.append(String.format("\\u%04x", character));
                  } else {
                    quoted.append((char) character);
                  }
                }
              }
            });
    return quoted.append('"').toString();
  }
}
