package com.bondplatform.dataprocessing.canonical.domain;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A contract path: properties from the document root, {@code $.a.b}, optionally ending in {@code
 * [*]} to select every entry of an array, {@code $.a.b[*]}. No other JSONPath syntax is supported.
 *
 * @param properties the property names, in order from the root
 * @param everyEntry whether the path selects every entry of the array at its end
 */
public record JsonPath(List<String> properties, boolean everyEntry) {

  private static final Pattern SHAPE = Pattern.compile("\\$(\\.[A-Za-z_][A-Za-z0-9_]*)+(\\[\\*])?");
  private static final String EVERY_ENTRY = "[*]";

  /** Copies the properties. */
  public JsonPath {
    properties = List.copyOf(properties);
    if (properties.isEmpty()) {
      throw new IllegalArgumentException("A path names at least one property");
    }
  }

  /**
   * Parses a path written as in a contract.
   *
   * @throws IllegalArgumentException if the path does not look like {@code $.a.b} or {@code
   *     $.a.b[*]}
   */
  public static JsonPath parse(String path) {
    if (!SHAPE.matcher(path).matches()) {
      throw new IllegalArgumentException("Unsupported path '" + path + "'");
    }
    boolean everyEntry = path.endsWith(EVERY_ENTRY);
    String properties = path.substring(2, path.length() - (everyEntry ? EVERY_ENTRY.length() : 0));
    return new JsonPath(Arrays.asList(properties.split("\\.")), everyEntry);
  }

  /** Returns the path as written in a contract. */
  @Override
  public String toString() {
    return "$." + String.join(".", properties) + (everyEntry ? EVERY_ENTRY : "");
  }
}
