package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.shared.domain.JobId;

/**
 * What the worker reads about a job before running it.
 *
 * @param attemptCount how many attempts have started so far
 * @param eventType the submission's {@code data.event_type}
 * @param fetchEventId the submission's {@code data.event_id}, the fetch trigger's identity
 * @param runId the fetch service's delivery run
 * @param inputsJson the submission's inputs, as JSON
 * @param contracts the contract versions pinned at admission, used by every attempt
 */
public record StoredJob(
    JobId id,
    JobStatus status,
    int attemptCount,
    DatasetUrn dataset,
    String subject,
    String eventType,
    String fetchEventId,
    String runId,
    OrderingGroup orderingGroup,
    String inputsJson,
    ManifestLocation manifest,
    String datasetFingerprint,
    PinnedContractVersions contracts) {}
