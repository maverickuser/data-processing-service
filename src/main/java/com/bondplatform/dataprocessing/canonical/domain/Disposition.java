package com.bondplatform.dataprocessing.canonical.domain;

/** What becomes of a canonical row once the whole file is known (LLD section 4.3). */
public enum Disposition {
  /** The row is valid and is the last valid occurrence of its ISIN in the file. */
  ACCEPTED,
  /** At least one field or row-level rule fails. */
  QUARANTINED_INVALID,
  /** The row is valid, but a later valid row has the same ISIN. */
  QUARANTINED_SUPERSEDED
}
