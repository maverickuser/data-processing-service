package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Stores outbox events in {@code data_processing.outbox_events}. */
@Repository
public class JdbcOutboxEventStore implements OutboxEventStore {

  static final String INSERT =
      """
      INSERT INTO data_processing.outbox_events
        (id, destination, message_group, ordering_key, payload, status, next_attempt_at,
         created_at)
      VALUES
        (:id, :destination, :messageGroup, :orderingKey, :payload::jsonb, 'PENDING', :createdAt,
         :createdAt)
      """;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the store. */
  public JdbcOutboxEventStore(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(NewOutboxEvent event) {
    OffsetDateTime createdAt = event.createdAt().atOffset(ZoneOffset.UTC);
    jdbc.update(
        INSERT,
        new MapSqlParameterSource()
            .addValue("id", event.id())
            .addValue("destination", event.destination().name())
            .addValue("messageGroup", event.messageGroup())
            .addValue("orderingKey", event.orderingKey())
            .addValue("payload", event.payloadJson())
            .addValue("createdAt", createdAt));
  }
}
