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
  /** A CSV lacks a header the source contract selects. */
  REQUIRED_HEADER_MISSING,
  /** Two CSV headers are the same after trimming and case-folding, selected or not. */
  DUPLICATE_HEADER
}
