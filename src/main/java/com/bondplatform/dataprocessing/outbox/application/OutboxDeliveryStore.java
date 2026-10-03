package com.bondplatform.dataprocessing.outbox.application;

import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The delivery state of outbox events, as the dispatcher needs it. Recording new events is {@link
 * OutboxEventStore}'s job.
 */
public interface OutboxDeliveryStore {

  /** Returns those of the given events that are still pending, in no particular order. */
  List<OutboxEvent> findPending(Collection<UUID> ids);

  /**
   * Returns pending events that are due at the given time and are the oldest pending event of their
   * group, oldest first, at most {@code limit} of them. An event without a group is always the
   * oldest of its group.
   */
  List<OutboxEvent> findDueGroupHeads(Instant now, int limit);

  /** Tells whether a pending event of the same destination and group comes before this one. */
  boolean hasOlderPending(OutboxEvent event);

  /** Records that the event was delivered; an event already delivered is left as it is. */
  void markDelivered(UUID id, Instant deliveredAt);

  /**
   * Records a failed attempt: the count of failed attempts, when to try next, and why it failed. An
   * event already delivered is left as it is.
   */
  void recordFailedAttempt(UUID id, int failedAttempts, Instant nextAttemptAt, String error);
}
