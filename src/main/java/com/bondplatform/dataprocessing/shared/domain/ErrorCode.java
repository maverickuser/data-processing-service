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
  /** A selected field holds a JSON value of the wrong kind, such as a number where text is due. */
  INVALID_TYPE
}
