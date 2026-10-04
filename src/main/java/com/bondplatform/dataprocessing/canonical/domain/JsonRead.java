package com.bondplatform.dataprocessing.canonical.domain;

/** The result of reading one JSON source file. */
public sealed interface JsonRead {

  /** The file is one well-formed JSON value. */
  record Parsed(JsonValue root) implements JsonRead {}

  /**
   * The file is skipped with an error (LLD section 13.7): it is not UTF-8, not well-formed JSON, or
   * repeats a property name inside one object.
   *
   * @param detail a message safe to show, naming where the problem is but quoting no value
   */
  record Malformed(String detail) implements JsonRead {}
}
