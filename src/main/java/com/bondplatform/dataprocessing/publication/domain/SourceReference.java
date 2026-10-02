package com.bondplatform.dataprocessing.publication.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a stored value came from: enough to find its line in the run's canonical file.
 *
 * @param requestId the ingestion request (the API job) that supplied the value
 * @param sourceFile the source file's name, without any bucket or folder
 * @param location the CSV record number or the JSONPath within that file
 */
public record SourceReference(UUID requestId, String sourceFile, String location) {

  /** Rejects a missing or blank part. */
  public SourceReference {
    Objects.requireNonNull(requestId, "requestId");
    if (sourceFile.isBlank() || location.isBlank()) {
      throw new IllegalArgumentException("Source file and location must not be blank");
    }
  }
}
