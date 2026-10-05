package com.bondplatform.dataprocessing.publication.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The security fields one request supplies valid values for, each with where its value came from,
 * and the fields it clears.
 *
 * <p>A field with no valid value in the request is absent, so it never erases a stored value (LLD
 * section 13.5). The only exception is a cleared field: an explicit {@code Unsecured} status makes
 * the coverage fields inapplicable, so their stored values are removed (LLD section 15.2).
 *
 * @param fields the values by internal field name, such as {@code couponRate}
 * @param cleared the fields whose stored value is removed; none of them is in {@code fields}
 */
public record SecurityScalars(Map<String, Field> fields, Set<String> cleared) {

  /**
   * Copies the map and set, keeping their order.
   *
   * @throws IllegalArgumentException if a field is both given a value and cleared
   */
  public SecurityScalars {
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    cleared = Collections.unmodifiableSet(new LinkedHashSet<>(cleared));
    for (String name : cleared) {
      if (fields.containsKey(name)) {
        throw new IllegalArgumentException("Field " + name + " cannot be both set and cleared");
      }
    }
  }

  /** Creates scalars that clear nothing. */
  public SecurityScalars(Map<String, Field> fields) {
    this(fields, Set.of());
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

  /** Returns these scalars with the given fields cleared instead of set. */
  public SecurityScalars clearing(Set<String> names) {
    Map<String, Field> kept = new LinkedHashMap<>(fields);
    kept.keySet().removeAll(names);
    Set<String> all = new LinkedHashSet<>(cleared);
    all.addAll(names);
    return new SecurityScalars(kept, all);
  }

  /**
   * Returns only the fields whose value differs from the stored one, in the same order.
   *
   * <p>An identical value is left out, so neither the value, its source, nor the security's update
   * time changes (LLD section 13.5). Clearing a field that has no stored value is no change either.
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
    Set<String> removed = new LinkedHashSet<>(cleared);
    removed.retainAll(stored.keySet());
    return new SecurityScalars(changed, removed);
  }

  /** Returns whether nothing is set or cleared. */
  public boolean isEmpty() {
    return fields.isEmpty() && cleared.isEmpty();
  }
}
