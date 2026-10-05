package com.bondplatform.dataprocessing.shared.domain;

/**
 * Stable codes for problems found in source data.
 *
 * <p>A code appears in the error-review API and is part of the public contract: renaming a constant
 * is a breaking change. Codes are added here by the pull request that first produces them.
 */
public enum ErrorCode {
  /** A number uses comma grouping that is neither valid Western nor valid Indian grouping. */
  INVALID_NUMBER_GROUPING,
  /** Text that should be a number is not a plain decimal with a dot as the decimal separator. */
  INVALID_DECIMAL,
  /** A count such as traded volume has a non-zero fraction. */
  NOT_WHOLE_NUMBER,
  /** A number that must not be negative is below zero. */
  NEGATIVE_VALUE,
  /** Text that should be a date is not a real calendar date in an accepted format. */
  INVALID_DATE,
  /** A selected field holds a JSON value of the wrong kind, such as a number where text is due. */
  INVALID_TYPE,
  /** A file has no data: it is empty or has a header and nothing after it. */
  EMPTY_FILE,
  /** A CSV is not valid UTF-8 RFC 4180, or a record has another number of cells than the header. */
  MALFORMED_CSV,
  /**
   * A JSON file is not valid UTF-8, not one well-formed JSON value, or repeats a property name in
   * one object; the file is skipped and the others continue.
   */
  MALFORMED_JSON,
  /**
   * A listed JSON file's content does not match the manifest's checksum or size; the file is
   * skipped and the others continue (LLD section 19). A CSV mismatch fails the job instead.
   */
  CHECKSUM_MISMATCH,
  /** A CSV lacks a header the source contract selects. */
  REQUIRED_HEADER_MISSING,
  /** Two CSV headers are the same after trimming and case-folding, selected or not. */
  DUPLICATE_HEADER,
  /** A field the contract requires is blank, such as a CSV row without an ISIN. */
  REQUIRED_VALUE_MISSING,
  /** Two prices in one row contradict each other, such as a high price below the low price. */
  PRICE_INCONSISTENT,
  /** A valid CSV row is replaced by a later valid row with the same ISIN in the same file. */
  DUPLICATE_ISIN_SUPERSEDED,
  /**
   * An asset-cover section states {@code Unsecured} but also supplies coverage or assets; the
   * status is applied and the coverage or assets are ignored.
   */
  CONFLICTING_COLLATERAL_DATA
}
