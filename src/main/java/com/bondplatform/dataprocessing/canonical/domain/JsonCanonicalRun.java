package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;

/**
 * The processing attempt of a JSON request a canonical file belongs to, and the facts every line
 * repeats (LLD section 17.5).
 *
 * @param jobId the ingestion request
 * @param attemptNumber the one-based processing attempt
 * @param isin the ISIN the request's file names share
 * @param sourceContractVersion the pinned stage-1 contract
 * @param mappingContractVersion the pinned stage-2 contract
 */
public record JsonCanonicalRun(
    JobId jobId,
    int attemptNumber,
    Isin isin,
    String sourceContractVersion,
    String mappingContractVersion) {

  /** Checks the attempt number. */
  public JsonCanonicalRun {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("Attempt numbers start at 1");
    }
  }

  /** Returns the run's object key, {@code canonical/{jobId}/attempt-{n}/canonical.jsonl}. */
  public String objectKey() {
    return CanonicalRun.objectKey(jobId, attemptNumber);
  }
}
