package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The outbox store against PostgreSQL. */
class OutboxEventStoreIT extends PostgresIntegrationTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-26T10:00:00Z");

  @Autowired private OutboxEventStore outbox;

  @Test
  void storesPendingEventWithEveryColumn() {
    UUID id = UUID.randomUUID();

    outbox.append(
        new NewOutboxEvent(
            id,
            OutboxDestination.FILE_PROCESSING,
            "trade-date:2026-01-01",
            42L,
            "{\"jobId\": \"job-1\"}",
            CREATED_AT));

    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT id, destination, message_group, ordering_key, payload ->> 'jobId' AS job,
                  status, attempt_count, last_error, delivered_at,
                  next_attempt_at = created_at AS due_at_creation,
                  created_at = TIMESTAMPTZ '2026-09-26T10:00:00Z' AS created_as_given
                FROM data_processing.outbox_events
                """)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("id", id)
        .containsEntry("destination", "FILE_PROCESSING")
        .containsEntry("message_group", "trade-date:2026-01-01")
        .containsEntry("ordering_key", 42L)
        .containsEntry("job", "job-1")
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("last_error", null)
        .containsEntry("delivered_at", null)
        .containsEntry("due_at_creation", true)
        .containsEntry("created_as_given", true);
  }

  @Test
  void storesEventWithoutGroupOrOrder() {
    outbox.append(
        new NewOutboxEvent(
            UUID.randomUUID(), OutboxDestination.SECURITY_DETAILS, null, null, "{}", CREATED_AT));

    Map<String, Object> row =
        jdbc.sql(
                "SELECT destination, message_group, ordering_key"
                    + " FROM data_processing.outbox_events")
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("destination", "SECURITY_DETAILS")
        .containsEntry("message_group", null)
        .containsEntry("ordering_key", null);
  }
}
