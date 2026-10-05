package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;

/**
 * How a JSON request's files and fields fared in stage 1 (LLD section 20.3).
 *
 * @param filesListed files the manifest lists
 * @param filesProcessed files that were read
 * @param filesSkipped files skipped with an error, such as a malformed file
 * @param fieldsRejected selected fields that failed validation
 */
public record JsonFileCounts(
    int filesListed, int filesProcessed, int filesSkipped, int fieldsRejected) {

  /**
   * Checks that every listed file was either processed or skipped.
   *
   * @throws IllegalArgumentException if a count is negative or the files do not add up
   */
  public JsonFileCounts {
    if (filesProcessed < 0 || filesSkipped < 0 || fieldsRejected < 0) {
      throw new IllegalArgumentException("Counts must not be negative");
    }
    if (filesProcessed + filesSkipped != filesListed) {
      throw new IllegalArgumentException(
          "Processed and skipped files must add up to the listed files: "
              + filesProcessed
              + " + "
              + filesSkipped
              + " != "
              + filesListed);
    }
  }

  /**
   * Returns the job outcome once the request's changes are known.
   *
   * @param status the request's status, decided by stage 2
   * @param scalarFieldsChanged security fields set or cleared; a coverage field cleared by an
   *     {@code Unsecured} status counts when it held a value
   * @param collectionEntriesAppended collection entries the security did not have
   * @param errorCount the errors the attempt recorded
   */
  public JobOutcome outcome(
      JobStatus status, int scalarFieldsChanged, int collectionEntriesAppended, int errorCount) {
    // Whole numbers need no escaping, so the domain builds this JSON without a library.
    String counts =
        "{\"filesListed\":"
            + filesListed
            + ",\"filesProcessed\":"
            + filesProcessed
            + ",\"filesSkipped\":"
            + filesSkipped
            + ",\"scalarFieldsChanged\":"
            + scalarFieldsChanged
            + ",\"collectionEntriesAppended\":"
            + collectionEntriesAppended
            + ",\"fieldsRejected\":"
            + fieldsRejected
            + "}";
    return new JobOutcome(status, counts, errorCount);
  }
}
