package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * A validated record with its final disposition (LLD sections 4.2 to 4.3).
 *
 * @param record the validated record
 * @param disposition what becomes of the record
 * @param supersededBy for a superseded record, the number of the later record that won
 */
public record CanonicalRow(
    CanonicalRecord record, Disposition disposition, OptionalLong supersededBy) {

  /** Checks that only a superseded row names the record that superseded it. */
  public CanonicalRow {
    if (supersededBy.isPresent() != (disposition == Disposition.QUARANTINED_SUPERSEDED)) {
      throw new IllegalArgumentException(
          "Only a superseded row names a winner: " + disposition + " " + supersededBy);
    }
  }

  /**
   * Returns the row's own problems, then {@code DUPLICATE_ISIN_SUPERSEDED} for a superseded row.
   * Field problems stay with their fields.
   */
  public List<ValidationIssue> rowErrors() {
    List<ValidationIssue> errors = new ArrayList<>(record.rowErrors());
    supersededBy.ifPresent(
        winner ->
            errors.add(
                new ValidationIssue(
                    ErrorCode.DUPLICATE_ISIN_SUPERSEDED,
                    "Record " + winner + " has the same ISIN and supersedes this record.")));
    return List.copyOf(errors);
  }
}
