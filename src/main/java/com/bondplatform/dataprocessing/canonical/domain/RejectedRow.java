package com.bondplatform.dataprocessing.canonical.domain;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A quarantined row with its reviewable issues, numbered in the run's issue order (LLD sections 4.3
 * and 17.5).
 *
 * @param row the quarantined row
 * @param isin the row's normalized ISIN, or {@code null} if it has none
 * @param issues the row's issues: each field's errors in field order, then the row's errors
 */
public record RejectedRow(CanonicalRow row, @Nullable String isin, List<ReviewIssue> issues) {

  /** Checks that the row was not accepted. */
  public RejectedRow {
    if (row.disposition() == Disposition.ACCEPTED) {
      throw new IllegalArgumentException("Record " + row.record().recordNumber() + " is accepted");
    }
    issues = List.copyOf(issues);
  }

  /**
   * Lists a quarantined row's issues, numbering them from {@code firstSequenceNumber}.
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
