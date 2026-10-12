package com.bondplatform.dataprocessing.canonical.domain;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A row with reviewable issues, numbered in the run's issue order (LLD sections 4.3 and 17.5): a
 * quarantined row, or an accepted row with a field whose invalid value was ignored.
 *
 * @param row the row
 * @param isin the row's normalized ISIN, or {@code null} if it has none
 * @param issues the row's issues: each field's errors in field order, then the row's errors
 */
public record RejectedRow(CanonicalRow row, @Nullable String isin, List<ReviewIssue> issues) {

  /** Checks that an accepted row has issues, which only its ignored fields can give it. */
  public RejectedRow {
    if (row.disposition() == Disposition.ACCEPTED && issues.isEmpty()) {
      throw new IllegalArgumentException(
          "Record " + row.record().recordNumber() + " is accepted and has no issues");
    }
    issues = List.copyOf(issues);
  }

  /** Returns whether the row itself is kept for review, which only a quarantined row is. */
  public boolean quarantined() {
    return row.disposition() != Disposition.ACCEPTED;
  }

  /**
   * Lists a row's issues, numbering them from {@code firstSequenceNumber}. An accepted row has
   * issues only for its ignored fields; its caller must not build one without them.
   *
   * @param isinField the canonical field holding the ISIN
   */
  public static RejectedRow of(CanonicalRow row, String isinField, long firstSequenceNumber) {
    List<ReviewIssue> issues = new ArrayList<>();
    long sequenceNumber = firstSequenceNumber;
    for (CanonicalField field : row.record().fields().values()) {
      for (ValidationIssue issue : field.errors()) {
        issues.add(new ReviewIssue(sequenceNumber++, issue, field));
      }
    }
    for (ValidationIssue issue : row.rowErrors()) {
      issues.add(new ReviewIssue(sequenceNumber++, issue, null));
    }
    return new RejectedRow(row, row.record().field(isinField).normalizedValue(), issues);
  }

  /**
   * One issue as the error-review API shows it.
   *
   * @param sequenceNumber the issue's place in the run's order, from 1
   * @param issue the code and message
   * @param field the field the issue is about, or {@code null} for a row-level issue
   */
  public record ReviewIssue(
      long sequenceNumber, ValidationIssue issue, @Nullable CanonicalField field) {}
}
