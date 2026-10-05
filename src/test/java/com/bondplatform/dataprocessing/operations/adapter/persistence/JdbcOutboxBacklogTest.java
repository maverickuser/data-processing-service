package com.bondplatform.dataprocessing.operations.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Checks what the backlog reads through JDBC; the query runs against PostgreSQL in CI's ITs. */
class JdbcOutboxBacklogTest {

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcOutboxBacklog backlog = new JdbcOutboxBacklog(jdbc);

  @Test
  void returnsWhenTheOldestPendingEventWasCreated() {
    when(jdbc.queryForObject(
            eq(JdbcOutboxBacklog.OLDEST_PENDING),
            any(SqlParameterSource.class),
            eq(OffsetDateTime.class)))
        .thenReturn(OffsetDateTime.of(2026, 10, 6, 7, 30, 0, 0, ZoneOffset.UTC));

    assertThat(backlog.oldestPendingCreatedAt()).contains(Instant.parse("2026-10-06T07:30:00Z"));
  }

  @Test
  void returnsEmptyWhenNothingIsPending() {
    assertThat(backlog.oldestPendingCreatedAt()).isEmpty();
  }

  @Test
  void looksOnlyAtPendingEvents() {
    assertThat(JdbcOutboxBacklog.OLDEST_PENDING).contains("status = 'PENDING'");
  }
}
