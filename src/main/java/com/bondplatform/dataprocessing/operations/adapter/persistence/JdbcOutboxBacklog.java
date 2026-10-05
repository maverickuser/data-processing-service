package com.bondplatform.dataprocessing.operations.adapter.persistence;

import com.bondplatform.dataprocessing.operations.application.OutboxBacklog;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Reads the outbox backlog from {@code data_processing.outbox_events}. */
@Repository
public class JdbcOutboxBacklog implements OutboxBacklog {

  static final String OLDEST_PENDING =
      "SELECT min(created_at) FROM data_processing.outbox_events WHERE status = 'PENDING'";

  private final NamedParameterJdbcOperations jdbc;

  /** Creates a backlog read through the given JDBC operations. */
  public JdbcOutboxBacklog(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Instant> oldestPendingCreatedAt() {
    return Optional.ofNullable(
            jdbc.queryForObject(OLDEST_PENDING, new MapSqlParameterSource(), OffsetDateTime.class))
        .map(OffsetDateTime::toInstant);
  }
}
