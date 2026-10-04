package com.bondplatform.dataprocessing.canonical.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Chooses the last valid row for each ISIN in a file (LLD section 5.4).
 *
 * <p>The resolver sees the file twice. First every record is {@linkplain #observe observed}, which
 * remembers only each ISIN's last valid record number. Then each record is {@linkplain #resolve
 * resolved} to its disposition. Invalid rows keep their errors and never displace a valid row; an
 * ISIN with no valid row has no accepted row. ISINs are compared after the contract's
 * normalization, so case and surrounding spaces do not matter.
 */
public final class DuplicateIsinResolver {

  private final String key;
  private final Map<String, Long> lastValid = new HashMap<>();

  /** Creates a resolver that identifies duplicates by the named canonical field. */
  public DuplicateIsinResolver(String key) {
    this.key = key;
  }

  /** Remembers a valid record as the latest occurrence of its ISIN so far. */
  public void observe(CanonicalRecord record) {
    if (record.validationStatus() == ValidationStatus.PASSED) {
      lastValid.put(isin(record), record.recordNumber());
    }
  }

  /**
   * Returns a record's disposition once the whole file has been observed.
   *
   * @throws IllegalStateException if a valid record was not observed first
   */
  public CanonicalRow resolve(CanonicalRecord record) {
    if (record.validationStatus() == ValidationStatus.FAILED) {
      return new CanonicalRow(record, Disposition.QUARANTINED_INVALID, OptionalLong.empty());
    }
    Long winner = lastValid.get(isin(record));
    if (winner == null || winner < record.recordNumber()) {
      throw new IllegalStateException("Record " + record.recordNumber() + " was not observed");
    }
    return winner == record.recordNumber()
        ? new CanonicalRow(record, Disposition.ACCEPTED, OptionalLong.empty())
        : new CanonicalRow(record, Disposition.QUARANTINED_SUPERSEDED, OptionalLong.of(winner));
  }

  private String isin(CanonicalRecord record) {
    String isin = record.field(key).parsedValue();
    if (isin == null) {
      // The contract makes the key a required value, so a valid record always has one.
      throw new IllegalStateException("Record " + record.recordNumber() + " has no " + key);
    }
    return isin;
  }
}
