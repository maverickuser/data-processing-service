package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.LocalDate;

/**
 * The processing attempt a canonical file belongs to, and the facts every line repeats (LLD
 * sections 4.2 and 17.5).
 *
 * @param jobId the ingestion request
 * @param attemptNumber the one-based processing attempt
 * @param tradeDate the trading date the manifest names
 * @param sourceBucket the bucket of the source file
 * @param sourceKey the object key of the source file
 * @param sourceContractVersion the pinned stage-1 contract
 * @param mappingContractVersion the pinned stage-2 contract
 */
public record CanonicalRun(
    JobId jobId,
    int attemptNumber,
    LocalDate tradeDate,
    String sourceBucket,
    String sourceKey,
    String sourceContractVersion,
    String mappingContractVersion) {

  /** Checks the attempt number. */
  public CanonicalRun {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("Attempt numbers start at 1");
    }
  }

  /** Returns the run's object key, {@code canonical/{jobId}/attempt-{n}/canonical.jsonl}. */
  public String objectKey() {
    return "canonical/" + jobId.value() + "/attempt-" + attemptNumber + "/canonical.jsonl";
  }
}
