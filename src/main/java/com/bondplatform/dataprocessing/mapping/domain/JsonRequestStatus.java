package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import java.util.List;

/**
 * Decides the job status of a completely evaluated JSON request (LLD section 13.8).
 *
 * <ul>
 *   <li>No usable selected data in any file: {@code FAILED}. Placeholders, missing values, and
 *       invalid values are not usable.
 *   <li>Usable data and no errors: {@code COMPLETED}, even if every value equals the stored one.
 *   <li>Usable data and some field, file, or collateral errors: {@code COMPLETED_WITH_ERRORS}.
 * </ul>
 *
 * <p>Warnings, such as a payload ISIN that differs from the file name's, are not errors and do not
 * change the status.
 */
public final class JsonRequestStatus {

  private JsonRequestStatus() {}

  /**
   * Returns the status of a request whose files were all read or skipped.
   *
   * @param files stage 1's result for each file
   * @param errorCount the errors the attempt recorded
   */
  public static JobStatus of(List<JsonFileCanonical> files, long errorCount) {
    boolean usable =
        files.stream()
            .anyMatch(file -> file instanceof JsonFileCanonical.Read read && read.hasUsableData());
    if (!usable) {
      return JobStatus.FAILED;
    }
    return errorCount == 0 ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
  }
}
