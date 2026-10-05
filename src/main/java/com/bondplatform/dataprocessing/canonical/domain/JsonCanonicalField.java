package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One selected field of a JSON canonical record: where it came from, what it held, and what
 * validation made of it (LLD sections 4.2 and 13.4).
 *
 * <p>The source primitive is kept as read, so missing, {@code null}, placeholder, valid, and
 * invalid values stay distinguishable in canonical evidence. Parsed numbers are exact decimal
 * strings and dates are ISO dates.
 *
 * @param name the canonical field name, such as {@code original_face_value}
 * @param path the field's JSONPath in the file, such as {@code
 *     $.instrumentsVo.instruments.issuePrice}
 * @param presence whether the field carries a value to act on; anything but {@code PRESENT} is "no
 *     update". {@code MISSING} with status {@code FAILED} means the path could not be reached
 *     because of a {@link StructureIssue}; the status, not the presence, decides
 * @param rawValue what the file holds at the path, before any normalization; {@link
 *     SourceValue.Missing} when the path cannot be reached
 * @param normalizedValue the text after normalization, or {@code null} when there is none
 * @param parsedValue the typed value as a canonical string, or {@code null} when there is none
 * @param dataType the type the contract gives the field
 * @param validationStatus whether the field passed
 * @param errors every problem with the field itself; empty when it passed, and for an unreachable
 *     field, whose problem is reported once as a {@link StructureIssue}
 */
public record JsonCanonicalField(
    String name,
    String path,
    FieldPresence presence,
    SourceValue rawValue,
    @Nullable String normalizedValue,
    @Nullable String parsedValue,
    FieldType dataType,
    ValidationStatus validationStatus,
    List<ValidationIssue> errors) {

  /** Copies the errors. */
  public JsonCanonicalField {
    errors = List.copyOf(errors);
  }

  /** Returns whether the field holds a valid value that may update business data. */
  public boolean isUsable() {
    return validationStatus == ValidationStatus.PASSED && parsedValue != null;
  }
}
