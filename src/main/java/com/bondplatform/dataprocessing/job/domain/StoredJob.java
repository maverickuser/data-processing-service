package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.shared.domain.JobId;

/**
 * What the worker reads about a job before running it.
 *
 * @param attemptCount how many attempts have started so far
 * @param inputsJson the submission's inputs, as JSON
 * @param contracts the contract versions pinned at admission, used by every attempt
 */
public record StoredJob(
    JobId id,
    JobStatus status,
    int attemptCount,
    DatasetUrn dataset,
    String subject,
    OrderingGroup orderingGroup,
    String inputsJson,
    ManifestLocation manifest,
    String datasetFingerprint,
    PinnedContractVersions contracts) {}
