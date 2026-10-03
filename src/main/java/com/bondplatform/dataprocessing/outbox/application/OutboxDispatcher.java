package com.bondplatform.dataprocessing.outbox.application;

import com.bondplatform.dataprocessing.outbox.domain.BackoffPolicy;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers committed outbox events to their queues, in order within each group (LLD sections 21 and
 * 23.4).
 *
 * <p>An event is sent only when no older event of its group is pending. That alone keeps the order:
 * an event becomes delivered only after a successful send, so when a later event is sent every
 * earlier one is already in the queue. Two dispatchers may send the same event at once; the queue's
 * deduplication by event ID delivers it once. The dispatcher opens no transaction of its own and
 * holds no lock while a message is sent.
 *
 * <p>A failed send leaves the event pending with a later due time from {@link BackoffPolicy}; it is
 * retried indefinitely.
 */
public class OutboxDispatcher {

  /** The most sends one sweep attempts, so one invocation stays well inside its time limit. */
  static final int MAX_ATTEMPTS_PER_SWEEP = 500;

  static final int BATCH_SIZE = 50;

  private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
  private static final int MAX_ERROR_LENGTH = 1000;

  private final OutboxDeliveryStore store;
  private final QueuePublisher publisher;
  private final Clock clock;

  /** Creates the dispatcher. */
  public OutboxDispatcher(OutboxDeliveryStore store, QueuePublisher publisher, Clock clock) {
    this.store = store;
    this.publisher = publisher;
    this.clock = clock;
  }

  /**
   * Tries to deliver just-committed events at once, each only if no older event of its group is
   * pending. Call it after the transaction that recorded them has committed.
   *
   * <p>It never throws: whatever is not delivered here stays pending for the sweep, and the
   * caller's own outcome, such as a {@code 202}, must stand.
   */
  public void deliverCommitted(Collection<UUID> eventIds) {
    // The broad catch below is the agreed exception to the standards' rule: this method is the
    // outermost boundary of a best-effort step inside another use case's response path.
    try {
      List<OutboxEvent> pending =
          store.findPending(eventIds).stream()
              .sorted(
                  Comparator.comparing(
                          OutboxEvent::orderingKey,
                          Comparator.nullsFirst(Comparator.naturalOrder()))
                      .thenComparing(OutboxEvent::createdAt))
              .toList();
      for (OutboxEvent event : pending) {
        if (!store.hasOlderPending(event)) {
          deliver(event);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("Immediate outbox delivery stopped; the sweep will deliver the rest", e);
    }
  }

  /**
   * Delivers pending events that are due, group by group in order, until none is left or the sweep
   * has made {@link #MAX_ATTEMPTS_PER_SWEEP} attempts.
   *
   * <p>A batch in which every send fails does not end the sweep: each failed event is due again
   * only later, so the next batch holds other events. One unavailable queue therefore cannot keep
   * events for another queue waiting.
   *
   * @return how many events were delivered
   */
  public int sweep() {
    int attempts = 0;
    int delivered = 0;
    while (attempts < MAX_ATTEMPTS_PER_SWEEP) {
      List<OutboxEvent> heads =
          store.findDueGroupHeads(
              Instant.now(clock), Math.min(BATCH_SIZE, MAX_ATTEMPTS_PER_SWEEP - attempts));
      if (heads.isEmpty()) {
        break;
      }
      for (OutboxEvent event : heads) {
        attempts++;
        if (deliver(event)) {
          delivered++;
        }
      }
    }
    return delivered;
  }

  /** Sends one event and records the outcome; returns whether it was delivered. */
  private boolean deliver(OutboxEvent event) {
    try {
      publisher.publish(event);
    } catch (QueuePublishException e) {
      int failedAttempts = event.failedAttempts() + 1;
      Instant nextAttemptAt = Instant.now(clock).plus(BackoffPolicy.delayAfter(failedAttempts));
      store.recordFailedAttempt(event.id(), failedAttempts, nextAttemptAt, errorOf(e));
      LOG.warn(
          "Outbox event not delivered, eventId={}, failedAttempts={}, nextAttemptAt={}",
          event.id(),
          failedAttempts,
          nextAttemptAt,
          e);
      return false;
    }
    store.markDelivered(event.id(), Instant.now(clock));
    return true;
  }

  private static String errorOf(QueuePublishException e) {
    String message = String.valueOf(e.getMessage());
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }
}
