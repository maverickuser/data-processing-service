package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;

/**
 * An accepted submission as stored: what admission needs to recognise a replay and to queue the
 * job.
 *
 * @param idempotencyKey the producer's delivery run ID
 * @param payloadHash fingerprint of the event that was accepted
 * @param acceptanceSequence the order in which requests were accepted; later is larger
 * @param submittedAt when the request was accepted
 */
public record IngestionRequest(
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
