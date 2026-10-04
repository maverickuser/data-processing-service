package com.bondplatform.dataprocessing.canonical.domain;

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

  /** Returns these counts with one more record of the given disposition. */
  public RowCounts plus(Disposition disposition) {
    return new RowCounts(
        sourceRecords + 1,
        acceptedRows + (disposition == Disposition.ACCEPTED ? 1 : 0),
        invalidRows + (disposition == Disposition.QUARANTINED_INVALID ? 1 : 0),
        supersededRows + (disposition == Disposition.QUARANTINED_SUPERSEDED ? 1 : 0));
  }
}
