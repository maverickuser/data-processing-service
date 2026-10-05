package com.bondplatform.dataprocessing.operations.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the store sends to JDBC. Whether the deletes remove the right rows is proven against
 * PostgreSQL in {@code RetentionIT}.
 */
class JdbcRetentionStoreTest {

  private static final Instant CUTOFF = Instant.parse("2025-03-01T02:00:00Z");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcRetentionStore store = new JdbcRetentionStore(jdbc);

  static Stream<Arguments> deletes() {
    return Stream.of(
        Arguments.of(
            JdbcRetentionStore.DELETE_ISSUES,
            (BiFunction<JdbcRetentionStore, Instant, Integer>)
                (s, cutoff) -> s.deleteIssuesCreatedBefore(cutoff, 50)),
        Arguments.of(
            JdbcRetentionStore.DELETE_REJECTED_RECORDS,
            (BiFunction<JdbcRetentionStore, Instant, Integer>)
                (s, cutoff) -> s.deleteRejectedRecordsCreatedBefore(cutoff, 50)),
        Arguments.of(
            JdbcRetentionStore.DELETE_DELIVERED_EVENTS,
            (BiFunction<JdbcRetentionStore, Instant, Integer>)
                (s, cutoff) -> s.deleteDeliveredOutboxEventsBefore(cutoff, 50)));
  }

  @ParameterizedTest
  @MethodSource("deletes")
  void bindsTheCutoffInUtcAndTheBatchLimit(
      String sql, BiFunction<JdbcRetentionStore, Instant, Integer> delete) {
    when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(12);

    assertThat(delete.apply(store, CUTOFF)).isEqualTo(12);

    ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(eq(sql), params.capture());
    assertThat(params.getValue().getValue("cutoff"))
        .isEqualTo(OffsetDateTime.of(2025, 3, 1, 2, 0, 0, 0, ZoneOffset.UTC));
    assertThat(params.getValue().getValue("limit")).isEqualTo(50);
  }

  @Test
  void neverDeletesPendingEvents() {
    assertThat(JdbcRetentionStore.DELETE_DELIVERED_EVENTS).contains("status = 'DELIVERED'");
  }
}
