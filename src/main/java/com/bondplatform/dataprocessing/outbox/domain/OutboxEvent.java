package com.bondplatform.dataprocessing.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A stored event still waiting for delivery.
 *
 * <p>Its identity, payload, and creation time are fixed when it is recorded and are sent unchanged
 * on every attempt, so a consumer can recognise a repeated delivery.
 *
 * @param id the event's identity, also the queue's deduplication ID
 * @param messageGroup the ordering group, or null when the destination does not order messages
 * @param orderingKey the position within the group; smaller is delivered first
 * @param payloadJson the message body, as JSON
 * @param failedAttempts how many delivery attempts have failed so far
 * @param createdAt when the event was recorded
 */
public record OutboxEvent(
    UUID id,
    OutboxDestination destination,
    @Nullable String messageGroup,
    @Nullable Long orderingKey,
    String payloadJson,
    int failedAttempts,
    Instant createdAt) {

  /** Rejects an event missing its identity, destination, or time, or with a negative count. */
  public OutboxEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(createdAt, "createdAt");
    if (failedAttempts < 0) {
      throw new IllegalArgumentException("Failed attempts must not be negative");
    }
  }
}
