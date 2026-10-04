package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed JSON value. Numbers are kept exactly as {@link BigDecimal}, and an object keeps its
 * properties in document order, each name once (LLD sections 13.4 and 13.7).
 */
public sealed interface JsonValue {

  /** An object; property names are matched exactly, including case. */
  record JsonObject(Map<String, JsonValue> properties) implements JsonValue {

    /** Copies the properties, keeping their order. */
    public JsonObject {
      properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
  }

  /** An array. */
  record JsonArray(List<JsonValue> elements) implements JsonValue {

    /** Copies the elements. */
    public JsonArray {
      elements = List.copyOf(elements);
    }
  }

  /** A string. */
  record JsonString(String value) implements JsonValue {}

  /** A number, kept exactly: {@code 89400} and {@code 89400.00} keep their own scale. */
  record JsonNumber(BigDecimal value) implements JsonValue {}

  /** {@code true} or {@code false}. */
  record JsonBoolean(boolean value) implements JsonValue {}

  /** An explicit {@code null}. */
  record JsonNull() implements JsonValue {}

  /** Returns what this value is as a selected source value, before any normalization. */
  default SourceValue toSourceValue() {
    return switch (this) {
      case JsonObject ignored -> new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT);
      case JsonArray ignored -> new SourceValue.Structured(SourceValue.Structured.Kind.ARRAY);
      case JsonString string -> new SourceValue.Text(string.value());
      case JsonNumber number -> new SourceValue.Decimal(number.value());
      case JsonBoolean bool -> new SourceValue.Bool(bool.value());
      case JsonNull ignored -> new SourceValue.Null();
    };
  }
}
