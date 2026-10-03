package com.bondplatform.dataprocessing.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Test cases U-OBX-02 and U-OBX-03, with an in-memory store and queue. */
class OutboxDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-09-27T14:31:00Z");
  private static final String GROUP = "trade-date:2026-09-21";

  private final MutableClock clock = new MutableClock(NOW);
  private final InMemoryStore store = new InMemoryStore(clock);
  private final RecordingQueue queue = new RecordingQueue();
  private final OutboxDispatcher dispatcher = new OutboxDispatcher(store, queue, clock);

  // U-OBX-03
  @Test
  void committedEventIsSentAtOnceWhenNothingOlderIsPending() {
    final UUID event = store.append(GROUP, 1L);

    dispatcher.deliverCommitted(List.of(event));

    assertThat(queue.sentIds()).containsExactly(event);
    assertThat(store.isDelivered(event)).isTrue();
  }

  // U-OBX-03
  @Test
  void committedEventWaitsWhileAnOlderEventOfItsGroupIsPending() {
    final UUID older = store.append(GROUP, 1L);
    final UUID newer = store.append(GROUP, 2L);
    final UUID otherGroup = store.append("trade-date:2026-09-22", 3L);

    dispatcher.deliverCommitted(List.of(newer, otherGroup));

    assertThat(queue.sentIds()).containsExactly(otherGroup);
    assertThat(store.isDelivered(older)).isFalse();
    assertThat(store.isDelivered(newer)).isFalse();
  }

  @Test
  void committedEventsOfOneGroupAreSentInOrder() {
    final UUID first = store.append(GROUP, 1L);
    final UUID second = store.append(GROUP, 2L);

    dispatcher.deliverCommitted(List.of(second, first));

    assertThat(queue.sentIds()).containsExactly(first, second);
  }

  // U-OBX-03
  @Test
  void failedSendLeavesEventPendingWithLaterDueTimeAndDoesNotThrow() {
    final UUID event = store.append(GROUP, 1L);
    queue.failNext(1);

    assertThatNoException().isThrownBy(() -> dispatcher.deliverCommitted(List.of(event)));

    assertThat(store.isDelivered(event)).isFalse();
    assertThat(store.failedAttempts(event)).isEqualTo(1);
    assertThat(store.nextAttemptAt(event)).isEqualTo(NOW.plusSeconds(10));
    assertThat(store.lastError(event)).isEqualTo("queue refused");
  }

  @Test
  void eventBehindFailedOneInItsGroupIsNotSent() {
    final UUID first = store.append(GROUP, 1L);
    final UUID second = store.append(GROUP, 2L);
    queue.failNext(1);

    dispatcher.deliverCommitted(List.of(first, second));

    assertThat(queue.sentIds()).isEmpty();
    assertThat(store.isDelivered(second)).isFalse();
  }

  @Test
  void storageFailureAfterCommitDoesNotReachTheCaller() {
    final UUID event = store.append(GROUP, 1L);
    store.failReads = true;

    assertThatNoException().isThrownBy(() -> dispatcher.deliverCommitted(List.of(event)));
    assertThat(queue.sentIds()).isEmpty();
  }

  @Test
  void eventWithoutGroupIsSentWhateverElseIsPending() {
    store.append(GROUP, 1L);
    final UUID ungrouped = store.appendUngrouped();

    dispatcher.deliverCommitted(List.of(ungrouped));

    assertThat(queue.sentIds()).containsExactly(ungrouped);
  }

  @Test
  void alreadyDeliveredOrUnknownEventIsNotSentAgain() {
    final UUID event = store.append(GROUP, 1L);
    dispatcher.deliverCommitted(List.of(event));

    dispatcher.deliverCommitted(List.of(event, UUID.randomUUID()));
    dispatcher.deliverCommitted(List.of());

    assertThat(queue.sentIds()).containsExactly(event);
  }

  // U-OBX-02
  @Test
  void retriesSendTheSameIdentityPayloadAndTimeWithGrowingDelays() {
    final UUID event = store.append(GROUP, 1L);
    queue.failNext(2);

    dispatcher.deliverCommitted(List.of(event));
    clock.advance(Duration.ofSeconds(10));
    dispatcher.sweep();
    assertThat(store.nextAttemptAt(event)).isEqualTo(NOW.plusSeconds(10 + 20));
    clock.advance(Duration.ofSeconds(20));
    dispatcher.sweep();

    assertThat(queue.attempts)
        .hasSize(3)
        .extracting(OutboxEvent::id, OutboxEvent::payloadJson, OutboxEvent::createdAt)
        .containsOnly(tuple(event, "{\"n\":0}", NOW));
    assertThat(queue.attempts.get(0).createdAt()).isEqualTo(NOW);
    assertThat(store.isDelivered(event)).isTrue();
  }

  @Test
  void sweepDeliversEveryGroupInOrder() {
    final UUID a1 = store.append(GROUP, 1L);
    final UUID b1 = store.append("isin:INE121A07QY9", 2L);
    final UUID a2 = store.append(GROUP, 3L);
    final UUID b2 = store.append("isin:INE121A07QY9", 4L);

    assertThat(dispatcher.sweep()).isEqualTo(4);

    List<UUID> sent = queue.sentIds();
    assertThat(sent).containsExactlyInAnyOrder(a1, a2, b1, b2);
    assertThat(sent.indexOf(a1)).isLessThan(sent.indexOf(a2));
    assertThat(sent.indexOf(b1)).isLessThan(sent.indexOf(b2));
  }

  @Test
  void sweepHoldsBackOnlyTheGroupWhoseOldestEventFailed() {
    final UUID a1 = store.append(GROUP, 1L);
    final UUID a2 = store.append(GROUP, 2L);
    final UUID b1 = store.append("isin:INE121A07QY9", 3L);
    queue.failNext(1);

    assertThat(dispatcher.sweep()).isEqualTo(1);

    assertThat(queue.sentIds()).containsExactly(b1);
    assertThat(store.isDelivered(a1)).isFalse();
    assertThat(store.isDelivered(a2)).isFalse();
  }

  @Test
  void sweepLeavesEventsThatAreNotYetDue() {
    final UUID event = store.append(GROUP, 1L);
    queue.failNext(1);
    dispatcher.sweep();

    clock.advance(Duration.ofSeconds(9));
    assertThat(dispatcher.sweep()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(dispatcher.sweep()).isEqualTo(1);
    assertThat(store.isDelivered(event)).isTrue();
  }

  @Test
  void sweepStopsAtItsLimit() {
    for (int i = 0; i < OutboxDispatcher.MAX_ATTEMPTS_PER_SWEEP + 5; i++) {
      store.appendUngrouped();
    }

    assertThat(dispatcher.sweep()).isEqualTo(OutboxDispatcher.MAX_ATTEMPTS_PER_SWEEP);
    assertThat(dispatcher.sweep()).isEqualTo(5);
    assertThat(dispatcher.sweep()).isZero();
  }

  // Review finding O-1: one unavailable queue must not hold back another queue's events.
  @Test
  void batchesOfFailingEventsDoNotStopTheSweepBeforeOtherEventsAreTried() {
    for (int i = 0; i < 2 * OutboxDispatcher.BATCH_SIZE + 10; i++) {
      store.appendUngrouped();
    }
    clock.advance(Duration.ofSeconds(1));
    final UUID job = store.append(GROUP, 1L);
    queue.failNext(2 * OutboxDispatcher.BATCH_SIZE + 10);

    assertThat(dispatcher.sweep()).isEqualTo(1);

    assertThat(queue.sentIds()).containsExactly(job);
  }

  @Test
  void sweepStopsAfterItsAttemptsEvenWhenEverySendFails() {
    for (int i = 0; i < OutboxDispatcher.MAX_ATTEMPTS_PER_SWEEP + 5; i++) {
      store.appendUngrouped();
    }
    queue.failNext(Integer.MAX_VALUE);

    assertThat(dispatcher.sweep()).isZero();

    assertThat(queue.attempts).hasSize(OutboxDispatcher.MAX_ATTEMPTS_PER_SWEEP);
  }

  @Test
  void longErrorIsShortenedBeforeItIsStored() {
    final UUID event = store.append(GROUP, 1L);
    queue.failNext(1);
    queue.error = "x".repeat(5000);

    dispatcher.deliverCommitted(List.of(event));

    assertThat(store.lastError(event)).hasSize(1000);
  }

  /** Sends to a list, refusing the next few events when asked to. */
  private static final class RecordingQueue implements QueuePublisher {

    private final List<OutboxEvent> attempts = new ArrayList<>();
    private final List<OutboxEvent> sent = new ArrayList<>();
    private int failuresLeft;
    private String error = "queue refused";

    void failNext(int count) {
      failuresLeft = count;
    }

    List<UUID> sentIds() {
      return sent.stream().map(OutboxEvent::id).toList();
    }

    @Override
    public void publish(OutboxEvent event) {
      attempts.add(event);
      if (failuresLeft > 0) {
        failuresLeft--;
        throw new QueuePublishException(error, new IllegalStateException("unavailable"));
      }
      sent.add(event);
    }
  }

  /** Keeps events in memory with the same delivery rules as the database. */
  private static final class InMemoryStore implements OutboxDeliveryStore {

    private final Clock clock;
    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final Set<UUID> delivered = new HashSet<>();
    private boolean failReads;

    InMemoryStore(Clock clock) {
      this.clock = clock;
    }

    UUID append(@Nullable String group, @Nullable Long orderingKey) {
      UUID id = new UUID(0, rows.size() + 1L);
      store(
          new NewOutboxEvent(
              id,
              group == null
                  ? OutboxDestination.SECURITY_DETAILS
                  : OutboxDestination.FILE_PROCESSING,
              group,
              orderingKey,
              "{\"n\":" + rows.size() + "}",
              Instant.now(clock)));
      return id;
    }

    UUID appendUngrouped() {
      return append(null, null);
    }

    void store(NewOutboxEvent event) {
      rows.put(
          event.id(),
          new Row(
              new OutboxEvent(
                  event.id(),
                  event.destination(),
                  event.messageGroup(),
                  event.orderingKey(),
                  event.payloadJson(),
                  0,
                  event.createdAt()),
              event.createdAt(),
              null));
    }

    @Override
    public List<OutboxEvent> findPending(Collection<UUID> ids) {
      if (failReads) {
        throw new IllegalStateException("database unavailable");
      }
      return ids.stream()
          .filter(rows::containsKey)
          .filter(id -> !delivered.contains(id))
          .map(id -> row(id).event())
          .toList();
    }

    @Override
    public List<OutboxEvent> findDueGroupHeads(Instant now, int limit) {
      return rows.values().stream()
          .filter(row -> !delivered.contains(row.event().id()))
          .filter(row -> !row.nextAttemptAt().isAfter(now))
          .map(Row::event)
          .filter(event -> !hasOlderPending(event))
          .sorted(Comparator.comparing(OutboxEvent::createdAt))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean hasOlderPending(OutboxEvent event) {
      if (event.messageGroup() == null || event.orderingKey() == null) {
        return false;
      }
      return rows.values().stream()
          .map(Row::event)
          .filter(other -> !delivered.contains(other.id()))
          .anyMatch(
              other ->
                  event.messageGroup().equals(other.messageGroup())
                      && other.orderingKey() != null
                      && other.orderingKey() < event.orderingKey());
    }

    @Override
    public void markDelivered(UUID id, Instant deliveredAt) {
      delivered.add(id);
    }

    @Override
    public void recordFailedAttempt(
        UUID id, int failedAttempts, Instant nextAttemptAt, String error) {
      OutboxEvent event = row(id).event();
      rows.put(
          id,
          new Row(
              new OutboxEvent(
                  event.id(),
                  event.destination(),
                  event.messageGroup(),
                  event.orderingKey(),
                  event.payloadJson(),
                  failedAttempts,
                  event.createdAt()),
              nextAttemptAt,
              error));
    }

    boolean isDelivered(UUID id) {
      return delivered.contains(id);
    }

    int failedAttempts(UUID id) {
      return row(id).event().failedAttempts();
    }

    Instant nextAttemptAt(UUID id) {
      return row(id).nextAttemptAt();
    }

    String lastError(UUID id) {
      return Objects.requireNonNull(row(id).lastError());
    }

    private Row row(UUID id) {
      return Objects.requireNonNull(rows.get(id), "unknown event");
    }

    private record Row(OutboxEvent event, Instant nextAttemptAt, @Nullable String lastError) {}
  }

  /** A clock the test moves forward. */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
