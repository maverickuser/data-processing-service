package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;

/**
 * Everything recorded when a submission is accepted.
 *
 * @param idempotencyKey the producer's delivery run ID
 * @param payloadHash fingerprint of the submitted event, used to tell a replay from a conflict
 * @param eventSource the CloudEvent {@code source}
 * @param eventId the CloudEvent {@code id}
 * @param inputsJson the event's inputs, as JSON
 * @param submissionEventJson the whole submitted event, as JSON; the immutable admission evidence
 * @param contracts the contract versions every attempt of this job will use
 */
public record NewIngestionRequest(
    JobId id,
    String idempotencyKey,
    String payloadHash,
    DatasetUrn dataset,
    String subject,
    OrderingGroup orderingGroup,
    String eventSource,
    String eventId,
    String runId,
    String inputsJson,
    ManifestLocation manifest,
    String datasetFingerprint,
    String submissionEventJson,
    PinnedContractVersions contracts,
    Instant submittedAt) {

  /**
   * The source and mapping contract versions pinned at admission, with their content hashes.
   *
   * @param sourceHash fingerprint of the source contract file
   * @param mappingHash fingerprint of the mapping contract file
   */
  public record PinnedContractVersions(
      ContractId source, String sourceHash, ContractId mapping, String mappingHash) {}
}
