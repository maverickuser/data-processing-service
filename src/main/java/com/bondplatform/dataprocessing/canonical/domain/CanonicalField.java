package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.FieldType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One selected field of a canonical row: where it came from, what it held, and what validation made
 * of it (LLD section 4.2).
 *
 * <p>The raw value is always kept, even when the field fails. Parsed numbers are kept as exact
 * decimal strings, never as floating point; a negative number keeps its parsed value, which tells a
 * constraint failure apart from text that could not be parsed.
 *
 * @param name the canonical field name, such as {@code close_price}
 * @param sourceHeader the header the source contract selects the field by
 * @param columnIndex the field's one-based column in the file
 * @param rawValue the decoded cell text before normalization
 * @param normalizedValue the text after the contract's normalizers, or {@code null} when the field
 *     is blank or its text could not be normalized
 * @param parsedValue the typed value as a canonical string, or {@code null} when there is none
 * @param dataType the type the contract gives the field
 * @param validationStatus whether the field passed
 * @param errors every problem with the field; empty when it passed
 */
public record CanonicalField(
    String name,
    String sourceHeader,
    int columnIndex,
    String rawValue,
    @Nullable String normalizedValue,
    @Nullable String parsedValue,
    FieldType dataType,
    ValidationStatus validationStatus,
    List<ValidationIssue> errors) {

  /** Copies the errors. */
  public CanonicalField {
    errors = List.copyOf(errors);
  }

  /** Returns the parsed number when the field is a valid, populated number. */
  public Optional<BigDecimal> validNumber() {
    if (validationStatus != ValidationStatus.PASSED
        || parsedValue == null
        || dataType == FieldType.TEXT) {
      return Optional.empty();
    }
    return Optional.of(new BigDecimal(parsedValue));
  }
}
