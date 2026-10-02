package com.bondplatform.dataprocessing.shared.domain;

/**
 * Stable codes for problems found in source data.
 *
 * <p>A code appears in the error-review API and is part of the public contract: renaming a constant
 * is a breaking change. Codes are added here by the pull request that first produces them.
 */
public enum ErrorCode {
  /** A selected field holds a JSON value of the wrong kind, such as a number where text is due. */
  INVALID_TYPE,
  /** A contract names a normalization or validation rule that does not exist. */
  UNKNOWN_RULE
}
