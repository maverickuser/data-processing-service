package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * A section of a JSON file that has the wrong kind of value, such as an object where {@code
 * currentRatings} must be an array (LLD section 13.7). It is reported once for its path, however
 * many selected fields sit under it, and quarantines only that section.
 *
 * @param path the path of the value that has the wrong kind
 * @param issue the error, {@code INVALID_TYPE} with the expected and actual kinds
 */
public record StructureIssue(String path, ValidationIssue issue) {

  /** Returns the issue for a path that found the wrong kind of value. */
  public static StructureIssue of(JsonMatch.WrongStructure structure) {
    return new StructureIssue(
        structure.path(),
        new ValidationIssue(
            ErrorCode.INVALID_TYPE,
            "Expected an "
                + structure.expected()
                + " at "
                + structure.path()
                + " but found "
                + structure.actual()
                + "."));
  }
}
