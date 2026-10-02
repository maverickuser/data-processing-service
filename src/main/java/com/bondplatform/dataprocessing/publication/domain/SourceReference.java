package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.Objects;

/**
 * Where a stored value came from: enough to find its line in the run's canonical file.
 *
 * @param jobId the ingestion request (the API job) that supplied the value
 * @param sourceFile the source file's name, without any bucket or folder
 * @param location the CSV record number or the JSONPath within that file
 */
public record SourceReference(JobId jobId, String sourceFile, String location) {

  /** Rejects a missing or blank part. */
  public SourceReference {
    Objects.requireNonNull(jobId, "jobId");
    if (sourceFile.isBlank() || location.isBlank()) {
      throw new IllegalArgumentException("Source file and location must not be blank");
    }
  }
}
