package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.job.domain.IngestionRequest;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import java.util.List;
import java.util.Optional;

/** Stores ingestion requests. */
public interface IngestionRequestRepository {

  /**
   * Stores the request as {@code QUEUED} unless a request with the same idempotency key, or the
   * same event source and event ID, already exists.
   *
   * <p>The answer comes from the insert itself, so of two concurrent identical submissions exactly
   * one is told it was stored.
   *
   * @return the stored request with its acceptance sequence, or empty if one already existed
   */
  Optional<IngestionRequest> insertIfAbsent(NewIngestionRequest request);

  /**
   * Returns the requests that share the idempotency key or the event identity: none, one, or, when
   * a key and an event identity belong to different earlier submissions, two.
   */
  List<IngestionRequest> findByIdempotencyKeyOrEvent(
      String idempotencyKey, String eventSource, String eventId);
}
