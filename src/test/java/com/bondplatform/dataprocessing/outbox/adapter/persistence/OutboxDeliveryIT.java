package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.outbox.application.OutboxDeliveryStore;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.application.QueuePublishException;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Delivery state of outbox events against PostgreSQL, and U-OBX-03 with the real store. */
class OutboxDeliveryIT extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-27T14:31:00Z");
  private static final String GROUP = "trade-date:2026-09-21";

  @Autowired private OutboxEventStore events;
  @Autowired private OutboxDeliveryStore delivery;

  @Test
  void findsOnlyPendingEventsAmongThoseAsked() {
    final UUID pending = append(GROUP, 1L, T0);
    final UUID delivered = append(GROUP, 2L, T0);
    delivery.markDelivered(delivered, T0);

    assertThat(delivery.findPending(List.of(pending, delivered, UUID.randomUUID())))
        .extracting(OutboxEvent::id)
        .containsExactly(pending);
  }

  @Test
  void readsBackEveryFieldOfStoredEvent() {
    final UUID id = append(GROUP, 7L, T0);

    assertThat(delivery.findPending(List.of(id)))
        .containsExactly(
            new OutboxEvent(id, OutboxDestination.FILE_PROCESSING, GROUP, 7L, "{\"n\": 7}", 0, T0));
  }

  @Test
  void groupHeadIsTheOldestPendingEventOfEachGroupThatIsDue() {
    final UUID a1 = append(GROUP, 1L, T0);
    append(GROUP, 2L, T0);
    final UUID b1 = append("isin:INE121A07QY9", 3L, T0.plusSeconds(1));
    final UUID ungrouped = append(null, null, T0.plusSeconds(2));
    final UUID future = append("isin:INE002A08534", 4L, T0.plusSeconds(60));

    assertThat(delivery.findDueGroupHeads(T0.plusSeconds(5), 10))
        .extracting(OutboxEvent::id)
        .containsExactly(a1, b1, ungrouped)
        .doesNotContain(future);
    assertThat(delivery.findDueGroupHeads(T0.plusSeconds(5), 2)).hasSize(2);
  }

  @Test
  void groupWhoseOldestEventIsNotDueYetHasNoHead() {
    final UUID first = append(GROUP, 1L, T0);
    append(GROUP, 2L, T0);
    delivery.recordFailedAttempt(first, 1, T0.plusSeconds(10), "throttled");

    assertThat(delivery.findDueGroupHeads(T0.plusSeconds(5), 10)).isEmpty();
    assertThat(delivery.findDueGroupHeads(T0.plusSeconds(10), 10))
        .extracting(OutboxEvent::id)
        .containsExactly(first);
  }

  @Test
  void olderPendingEventIsFoundOnlyInTheSameDestinationAndGroup() {
    append(GROUP, 1L, T0);
    final UUID second = append(GROUP, 2L, T0);
    final UUID otherGroup = append("trade-date:2026-09-22", 3L, T0);

    assertThat(delivery.hasOlderPending(event(second))).isTrue();
    assertThat(delivery.hasOlderPending(event(otherGroup))).isFalse();
  }

  @Test
  void deliveryAndFailedAttemptsAreRecordedAndDeliveredEventIsNotChangedAgain() {
    final UUID id = append(GROUP, 1L, T0);

    delivery.recordFailedAttempt(id, 2, T0.plusSeconds(20), "throttled");
    delivery.markDelivered(id, T0.plusSeconds(30));
    delivery.recordFailedAttempt(id, 3, T0.plusSeconds(99), "late failure");

    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT status, attempt_count, last_error,
                  delivered_at = TIMESTAMPTZ '2026-09-27T14:31:30Z' AS delivered_as_given,
                  next_attempt_at = TIMESTAMPTZ '2026-09-27T14:31:20Z' AS next_as_given
                FROM data_processing.outbox_events
                """)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("status", "DELIVERED")
        .containsEntry("attempt_count", 2)
        .containsEntry("last_error", "throttled")
        .containsEntry("delivered_as_given", true)
        .containsEntry("next_as_given", true);
  }

  // U-OBX-03
  @Test
  void dispatcherSendsEachGroupInOrderAndRetriesAFailedHeadLater() {
    final UUID a1 = append(GROUP, 1L, T0);
    final UUID a2 = append(GROUP, 2L, T0);
    final UUID b1 = append("isin:INE121A07QY9", 3L, T0);
    List<UUID> sent = new ArrayList<>();
    List<UUID> refuseOnce = new ArrayList<>(List.of(a1));
    OutboxDispatcher dispatcher =
        new OutboxDispatcher(
            delivery,
            event -> {
              if (refuseOnce.remove(event.id())) {
                throw new QueuePublishException("throttled", new IllegalStateException());
              }
              sent.add(event.id());
            },
            Clock.fixed(T0, ZoneOffset.UTC));

    dispatcher.deliverCommitted(List.of(a2, b1));
    assertThat(sent).containsExactly(b1);

    assertThat(dispatcher.sweep()).isZero();
    assertThat(sent).containsExactly(b1);

    OutboxDispatcher later =
        new OutboxDispatcher(
            delivery,
            event -> sent.add(event.id()),
            Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC));
    assertThat(later.sweep()).isEqualTo(2);
    assertThat(sent).containsExactly(b1, a1, a2);
  }

  private UUID append(@Nullable String group, @Nullable Long orderingKey, Instant createdAt) {
    UUID id = UUID.randomUUID();
    events.append(
        new NewOutboxEvent(
            id,
            group == null ? OutboxDestination.SECURITY_DETAILS : OutboxDestination.FILE_PROCESSING,
            group,
            orderingKey,
            "{\"n\": " + orderingKey + "}",
            createdAt));
    return id;
  }

  private OutboxEvent event(UUID id) {
    return delivery.findPending(List.of(id)).get(0);
  }
}
