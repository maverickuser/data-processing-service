package com.bondplatform.dataprocessing.job.domain;

/**
 * How a finished job ended, as a dataset handler reports it.
 *
 * @param status a terminal status
 * @param countsJson the dataset's counts as JSON, shown by the status API
 * @param errorCount how many errors the final attempt recorded; warnings are not counted
 */
public record JobOutcome(JobStatus status, String countsJson, int errorCount) {

  /**
   * Rejects an outcome that does not end the job.
   *
   * @throws IllegalArgumentException if the status is not terminal, the counts are blank, or the
   *     error count is negative
   */
  public JobOutcome {
    if (!status.isTerminal()) {
      throw new IllegalArgumentException("A job outcome needs a terminal status, not " + status);
    }
    if (countsJson.isBlank()) {
      throw new IllegalArgumentException("Counts must not be blank");
    }
    if (errorCount < 0) {
      throw new IllegalArgumentException("Error count must not be negative");
    }
  }
}
