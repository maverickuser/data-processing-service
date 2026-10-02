package com.bondplatform.dataprocessing.job.application;

import com.bondplatform.dataprocessing.job.domain.AcceptedRequest;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
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
  Optional<AcceptedRequest> insertIfAbsent(NewIngestionRequest request);

  /**
   * Makes the current transaction the only one admitting to this ordering group until it ends.
   *
   * <p>Acceptance sequence numbers are handed out at insert, not at commit. Taking turns per group
   * makes the order of the numbers the order of the commits, so a later request can never be queued
   * ahead of an earlier one in the same group.
   */
  void lockOrderingGroup(OrderingGroup group);

  /**
   * Returns the requests that share the idempotency key or the event identity: none, one, or, when
   * a key and an event identity belong to different earlier submissions, two.
   */
  List<AcceptedRequest> findByIdempotencyKeyOrEvent(
      String idempotencyKey, String eventSource, String eventId);
}
