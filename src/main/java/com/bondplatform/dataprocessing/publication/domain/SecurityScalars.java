package com.bondplatform.dataprocessing.publication.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The security fields one request supplies valid values for, each with where its value came from.
 *
 * <p>A field with no valid value in the request is absent, so it never erases a stored value (LLD
 * section 13.5).
 *
 * @param fields the values by internal field name, such as {@code couponRate}
 */
public record SecurityScalars(Map<String, Field> fields) {

  /** Copies the map, keeping its order. */
  public SecurityScalars {
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  /**
   * One field's value and its source.
   *
   * @param value the typed value
   * @param source the job, file, and JSONPath the value was read from
   */
  public record Field(SecurityValue value, SourceReference source) {

    /** Rejects a missing part. */
    public Field {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(source, "source");
    }
  }

  /**
   * Returns only the fields whose value differs from the stored one, in the same order.
   *
   * <p>An identical value is left out, so neither the value, its source, nor the security's update
   * time changes (LLD section 13.5).
   *
   * @param stored the security's current values by internal field name; a field with no value is
   *     absent
   */
  public SecurityScalars changesFrom(Map<String, SecurityValue> stored) {
    Map<String, Field> changed = new LinkedHashMap<>();
    fields.forEach(
        (name, field) -> {
          if (!field.value().equals(stored.get(name))) {
            changed.put(name, field);
          }
        });
    return new SecurityScalars(changed);
  }

  /** Returns whether there are no fields. */
  public boolean isEmpty() {
    return fields.isEmpty();
  }
}
