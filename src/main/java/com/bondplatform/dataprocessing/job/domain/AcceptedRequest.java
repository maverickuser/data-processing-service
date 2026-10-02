package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;

/**
 * What admission needs to know about a stored ingestion request: enough to recognise a replay and
 * to queue the job. It is a view of the request, not the whole row; the worker and the read API
 * read the parts they need through their own types.
 *
 * @param idempotencyKey the producer's delivery run ID
 * @param payloadHash fingerprint of the event that was accepted
 * @param acceptanceSequence the order in which requests were accepted; later is larger
 * @param submittedAt when the request was accepted
 */
public record AcceptedRequest(
    JobId id,
    String idempotencyKey,
    String payloadHash,
    String eventSource,
    String eventId,
    String runId,
    OrderingGroup orderingGroup,
    long acceptanceSequence,
    JobStatus status,
    Instant submittedAt) {}
