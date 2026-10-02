package com.bondplatform.dataprocessing.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An event to deliver once the transaction that records it has committed.
 *
 * <p>Recording the event in the same transaction as the change it announces is what makes delivery
 * reliable: either both are stored or neither is (LLD sections 8.2 and 14.2).
 *
 * @param id the event's identity, also used to deduplicate delivery
 * @param messageGroup the ordering group, for a queue that delivers one message per group at a time
 * @param orderingKey the position within the group; smaller is delivered first
 * @param payloadJson the message body, as JSON
 * @param createdAt when the event was recorded; never changed by delivery retries
 */
public record NewOutboxEvent(
    UUID id,
    OutboxDestination destination,
    @Nullable String messageGroup,
    @Nullable Long orderingKey,
    String payloadJson,
    Instant createdAt) {

  /** Rejects an event missing its identity, destination, payload, or time. */
  public NewOutboxEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(createdAt, "createdAt");
    if (payloadJson.isBlank()) {
      throw new IllegalArgumentException("Payload must not be blank");
    }
  }
}
