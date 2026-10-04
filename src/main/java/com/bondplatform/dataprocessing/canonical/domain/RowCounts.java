package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;

/**
 * How many data records a completely evaluated CSV had, by disposition (LLD section 20.3).
 *
 * @param sourceRecords data records, excluding the header and blank lines
 * @param acceptedRows rows that update trades
 * @param invalidRows rows that fail validation
 * @param supersededRows valid rows replaced by a later row with the same ISIN
 */
public record RowCounts(
    long sourceRecords, long acceptedRows, long invalidRows, long supersededRows) {

  /** Counts for a file with no records yet. */
  public static final RowCounts NONE = new RowCounts(0, 0, 0, 0);

  /** Checks that every record has exactly one disposition. */
  public RowCounts {
    if (acceptedRows + invalidRows + supersededRows != sourceRecords) {
      throw new IllegalArgumentException(
          "Dispositions must add up to the source records: "
              + acceptedRows
              + " + "
              + invalidRows
              + " + "
              + supersededRows
              + " != "
              + sourceRecords);
    }
  }

  /**
   * Returns the job status these counts give a completely evaluated file (LLD section 9): {@link
   * JobStatus#COMPLETED} when every row was accepted, {@link JobStatus#COMPLETED_WITH_ERRORS} when
   * some were quarantined, and {@link JobStatus#FAILED} when none was accepted.
   */
  public JobStatus status() {
    if (acceptedRows == 0) {
      return JobStatus.FAILED;
    }
    return acceptedRows == sourceRecords ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
  }

  /**
   * Returns the job outcome of a completely evaluated file: its {@link #status()}, these counts as
   * the status API shows them (LLD section 20.3), and the error count.
   *
   * @param errorCount the issues the attempt recorded, superseded rows included
   * @throws ArithmeticException if the error count does not fit the job record
   */
  public JobOutcome outcome(long errorCount) {
    // Four whole numbers need no escaping, so the domain builds this JSON without a library.
    // Anything with text values must use a JSON writer instead.
    String counts =
        "{\"sourceRecords\":"
            + sourceRecords
            + ",\"acceptedRows\":"
            + acceptedRows
            + ",\"invalidRows\":"
            + invalidRows
            + ",\"supersededRows\":"
            + supersededRows
            + "}";
    return new JobOutcome(status(), counts, Math.toIntExact(errorCount));
  }

  /** Returns these counts with one more record of the given disposition. */
  public RowCounts plus(Disposition disposition) {
    return new RowCounts(
        sourceRecords + 1,
        acceptedRows + (disposition == Disposition.ACCEPTED ? 1 : 0),
        invalidRows + (disposition == Disposition.QUARANTINED_INVALID ? 1 : 0),
        supersededRows + (disposition == Disposition.QUARANTINED_SUPERSEDED ? 1 : 0));
  }
}
