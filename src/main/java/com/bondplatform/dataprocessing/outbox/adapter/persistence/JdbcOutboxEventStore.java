package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import com.bondplatform.dataprocessing.outbox.application.OutboxDeliveryStore;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Stores outbox events and their delivery state in {@code data_processing.outbox_events}. */
@Repository
public class JdbcOutboxEventStore implements OutboxEventStore, OutboxDeliveryStore {

  static final String INSERT =
      """
      INSERT INTO data_processing.outbox_events
        (id, destination, message_group, ordering_key, payload, status, next_attempt_at,
         created_at)
      VALUES
        (:id, :destination, :messageGroup, :orderingKey, :payload::jsonb, 'PENDING', :createdAt,
         :createdAt)
      """;

  static final String FIND_PENDING =
      """
      SELECT id, destination, message_group, ordering_key, payload::text AS payload, attempt_count,
        created_at
      FROM data_processing.outbox_events
      WHERE id IN (:ids) AND status = 'PENDING'
      """;

  static final String FIND_DUE_GROUP_HEADS =
      """
      SELECT e.id, e.destination, e.message_group, e.ordering_key, e.payload::text AS payload,
        e.attempt_count, e.created_at
      FROM data_processing.outbox_events e
      WHERE e.status = 'PENDING' AND e.next_attempt_at <= :now
        AND NOT EXISTS (
          SELECT 1 FROM data_processing.outbox_events o
          WHERE o.status = 'PENDING' AND o.destination = e.destination
            AND o.message_group = e.message_group AND o.ordering_key < e.ordering_key)
      ORDER BY e.created_at, e.id
      LIMIT :limit
      """;

  static final String HAS_OLDER_PENDING =
      """
      SELECT EXISTS (
        SELECT 1 FROM data_processing.outbox_events
        WHERE status = 'PENDING' AND destination = :destination
          AND message_group = :messageGroup AND ordering_key < :orderingKey)
      """;

  static final String MARK_DELIVERED =
      """
      UPDATE data_processing.outbox_events
      SET status = 'DELIVERED', delivered_at = :deliveredAt
      WHERE id = :id AND status = 'PENDING'
      """;

  static final String RECORD_FAILED_ATTEMPT =
      """
      UPDATE data_processing.outbox_events
      SET attempt_count = :failedAttempts, next_attempt_at = :nextAttemptAt, last_error = :error
      WHERE id = :id AND status = 'PENDING'
      """;

  private static final RowMapper<OutboxEvent> ROW_MAPPER = JdbcOutboxEventStore::map;

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

  @Override
  public List<OutboxEvent> findPending(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(FIND_PENDING, new MapSqlParameterSource("ids", List.copyOf(ids)), ROW_MAPPER);
  }

  @Override
  public List<OutboxEvent> findDueGroupHeads(Instant now, int limit) {
    return jdbc.query(
        FIND_DUE_GROUP_HEADS,
        new MapSqlParameterSource()
            .addValue("now", now.atOffset(ZoneOffset.UTC))
            .addValue("limit", limit),
        ROW_MAPPER);
  }

  @Override
  public boolean hasOlderPending(OutboxEvent event) {
    if (event.messageGroup() == null || event.orderingKey() == null) {
      return false;
    }
    Boolean exists =
        jdbc.queryForObject(
            HAS_OLDER_PENDING,
            new MapSqlParameterSource()
                .addValue("destination", event.destination().name())
                .addValue("messageGroup", event.messageGroup())
                .addValue("orderingKey", event.orderingKey()),
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public void markDelivered(UUID id, Instant deliveredAt) {
    jdbc.update(
        MARK_DELIVERED,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("deliveredAt", deliveredAt.atOffset(ZoneOffset.UTC)));
  }

  @Override
  public void recordFailedAttempt(
      UUID id, int failedAttempts, Instant nextAttemptAt, String error) {
    jdbc.update(
        RECORD_FAILED_ATTEMPT,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("failedAttempts", failedAttempts)
            .addValue("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
            .addValue("error", error));
  }

  private static OutboxEvent map(ResultSet row, int rowNumber) throws SQLException {
    return new OutboxEvent(
        row.getObject("id", UUID.class),
        OutboxDestination.valueOf(row.getString("destination")),
        row.getString("message_group"),
        row.getObject("ordering_key", Long.class),
        row.getString("payload"),
        row.getInt("attempt_count"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
