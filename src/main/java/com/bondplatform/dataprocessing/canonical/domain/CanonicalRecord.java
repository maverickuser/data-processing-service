package com.bondplatform.dataprocessing.canonical.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One validated CSV data record (LLD section 4.2). Its disposition is decided later, once every
 * record of the file is known, because duplicates are resolved across the file.
 *
 * @param recordNumber the record's one-based position with the header as record 1
 * @param fields every selected field, in the source contract's order
 * @param rowErrors problems that involve more than one field, such as inconsistent prices
 */
public record CanonicalRecord(
    long recordNumber, Map<String, CanonicalField> fields, List<ValidationIssue> rowErrors) {

  /** Copies the fields, keeping their order, and the row errors. */
  public CanonicalRecord {
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    rowErrors = List.copyOf(rowErrors);
  }

  /**
   * Returns a selected field.
   *
   * @throws IllegalArgumentException if the source contract does not select it
   */
  public CanonicalField field(String name) {
    CanonicalField field = fields.get(name);
    if (field == null) {
      throw new IllegalArgumentException("No selected field " + name);
    }
    return field;
  }

  /** Returns {@link ValidationStatus#FAILED} when any field or row-level rule fails. */
  public ValidationStatus validationStatus() {
    boolean fieldFailed =
        fields.values().stream()
            .anyMatch(field -> field.validationStatus() == ValidationStatus.FAILED);
    return fieldFailed || !rowErrors.isEmpty() ? ValidationStatus.FAILED : ValidationStatus.PASSED;
  }
}
