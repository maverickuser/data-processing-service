package com.bondplatform.dataprocessing.canonical.domain;

/** What becomes of one collection entry (LLD section 13.4). */
public enum EntryDisposition {
  /** The entry has at least one valid non-blank selected value and may be appended. */
  ACCEPTED,
  /** No selected value is valid and non-blank; the entry is skipped and its errors kept. */
  SKIPPED
}
