package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the store sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code OutboxEventStoreIT}.
 */
class JdbcOutboxEventStoreTest {

  private static final UUID ID = UUID.fromString("bfed74ba-84ee-45b6-8a7a-50fbb53a0cbb");
  private static final Instant CREATED_AT = Instant.parse("2026-09-26T10:00:00Z");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcOutboxEventStore store = new JdbcOutboxEventStore(jdbc);

  @Test
  void bindsEveryColumn() {
    store.append(
        new NewOutboxEvent(
            ID,
            OutboxDestination.FILE_PROCESSING,
            "isin:INE121A07QY9",
            42L,
            "{\"a\": 1}",
            CREATED_AT));

    SqlParameterSource bound = bound();
    assertThat(bound.getValue("id")).isEqualTo(ID);
    assertThat(bound.getValue("destination")).isEqualTo("FILE_PROCESSING");
    assertThat(bound.getValue("messageGroup")).isEqualTo("isin:INE121A07QY9");
    assertThat(bound.getValue("orderingKey")).isEqualTo(42L);
    assertThat(bound.getValue("payload")).isEqualTo("{\"a\": 1}");
    assertThat(bound.getValue("createdAt")).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
  }

  @Test
  void bindsAbsentGroupAndOrderAsNull() {
    store.append(
        new NewOutboxEvent(ID, OutboxDestination.SECURITY_DETAILS, null, null, "{}", CREATED_AT));

    SqlParameterSource bound = bound();
    assertThat(bound.getValue("destination")).isEqualTo("SECURITY_DETAILS");
    assertThat(bound.hasValue("messageGroup")).isTrue();
    assertThat(bound.getValue("messageGroup")).isNull();
    assertThat(bound.hasValue("orderingKey")).isTrue();
    assertThat(bound.getValue("orderingKey")).isNull();
  }

  @Test
  void statementRecordsTheEventAsPendingAndDueAtCreation() {
    assertThat(JdbcOutboxEventStore.INSERT)
        .contains("data_processing.outbox_events", "'PENDING'", ":payload::jsonb")
        .contains(":createdAt,\n   :createdAt)");
  }

  @Test
  void eventNeedsItsIdentityDestinationTimeAndPayload() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new NewOutboxEvent(
                    missing(), OutboxDestination.FILE_PROCESSING, null, null, "{}", CREATED_AT));
    assertThatNullPointerException()
        .isThrownBy(() -> new NewOutboxEvent(ID, missing(), null, null, "{}", CREATED_AT));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new NewOutboxEvent(
                    ID, OutboxDestination.FILE_PROCESSING, null, null, "{}", missing()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new NewOutboxEvent(
                    ID, OutboxDestination.FILE_PROCESSING, null, null, " ", CREATED_AT));
  }

  @Test
  void findsNothingWithoutAskingTheDatabaseWhenNoIdsAreGiven() {
    assertThat(store.findPending(List.of())).isEmpty();

    verifyNoInteractions(jdbc);
  }

  @Test
  void findsPendingEventsByIdentity() {
    store.findPending(List.of(ID));

    SqlParameterSource bound = queried(JdbcOutboxEventStore.FIND_PENDING);
    assertThat(bound.getValue("ids")).isEqualTo(List.of(ID));
    assertThat(JdbcOutboxEventStore.FIND_PENDING).contains("id IN (:ids)", "status = 'PENDING'");
  }

  @Test
  void findsDueGroupHeadsUpToTheLimit() {
    store.findDueGroupHeads(CREATED_AT, 50);

    SqlParameterSource bound = queried(JdbcOutboxEventStore.FIND_DUE_GROUP_HEADS);
    assertThat(bound.getValue("now")).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
    assertThat(bound.getValue("limit")).isEqualTo(50);
    assertThat(JdbcOutboxEventStore.FIND_DUE_GROUP_HEADS)
        .contains("next_attempt_at <= :now", "NOT EXISTS", "o.ordering_key < e.ordering_key")
        .contains("ORDER BY e.created_at, e.id", "LIMIT :limit");
  }

  @Test
  void asksForOlderPendingEventOfTheSameDestinationAndGroup() {
    when(jdbc.queryForObject(
            eq(JdbcOutboxEventStore.HAS_OLDER_PENDING),
            any(SqlParameterSource.class),
            eq(Boolean.class)))
        .thenReturn(true);

    assertThat(store.hasOlderPending(event("trade-date:2026-09-21", 7L))).isTrue();

    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc)
        .queryForObject(
            eq(JdbcOutboxEventStore.HAS_OLDER_PENDING), parameters.capture(), eq(Boolean.class));
    assertThat(parameters.getValue().getValue("destination")).isEqualTo("FILE_PROCESSING");
    assertThat(parameters.getValue().getValue("messageGroup")).isEqualTo("trade-date:2026-09-21");
    assertThat(parameters.getValue().getValue("orderingKey")).isEqualTo(7L);
  }

  @Test
  void eventWithoutGroupOrOrderHasNothingOlderAndNeedsNoQuery() {
    assertThat(store.hasOlderPending(event(null, null))).isFalse();
    assertThat(store.hasOlderPending(event("trade-date:2026-09-21", null))).isFalse();

    verifyNoInteractions(jdbc);
  }

  @Test
  void unansweredOlderPendingQueryCountsAsNothingOlder() {
    assertThat(store.hasOlderPending(event("trade-date:2026-09-21", 7L))).isFalse();
  }

  @Test
  void marksDeliveredOnlyWhilePending() {
    store.markDelivered(ID, CREATED_AT);

    SqlParameterSource bound = updated(JdbcOutboxEventStore.MARK_DELIVERED);
    assertThat(bound.getValue("id")).isEqualTo(ID);
    assertThat(bound.getValue("deliveredAt")).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
    assertThat(JdbcOutboxEventStore.MARK_DELIVERED)
        .contains("status = 'DELIVERED'", "WHERE id = :id AND status = 'PENDING'");
  }

  @Test
  void recordsFailedAttemptOnlyWhilePending() {
    store.recordFailedAttempt(ID, 3, CREATED_AT, "throttled");

    SqlParameterSource bound = updated(JdbcOutboxEventStore.RECORD_FAILED_ATTEMPT);
    assertThat(bound.getValue("failedAttempts")).isEqualTo(3);
    assertThat(bound.getValue("nextAttemptAt")).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
    assertThat(bound.getValue("error")).isEqualTo("throttled");
    assertThat(JdbcOutboxEventStore.RECORD_FAILED_ATTEMPT)
        .contains("WHERE id = :id AND status = 'PENDING'");
  }

  @Test
  void readsEveryColumnOfRowIncludingAbsentGroupAndOrder() throws SQLException {
    store.findPending(List.of(ID));
    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(ID);
    when(row.getString("destination")).thenReturn("SECURITY_DETAILS");
    when(row.getString("payload")).thenReturn("{}");
    when(row.getInt("attempt_count")).thenReturn(2);
    when(row.getObject("created_at", OffsetDateTime.class))
        .thenReturn(CREATED_AT.atOffset(ZoneOffset.ofHoursMinutes(5, 30)));

    assertThat(capturedRowMapper().mapRow(row, 0))
        .isEqualTo(
            new OutboxEvent(
                ID, OutboxDestination.SECURITY_DETAILS, null, null, "{}", 2, CREATED_AT));
  }

  @Test
  void storedEventNeedsItsIdentityDestinationTimeAndNonNegativeCount() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new OutboxEvent(
                    missing(), OutboxDestination.FILE_PROCESSING, null, null, "{}", 0, CREATED_AT));
    assertThatNullPointerException()
        .isThrownBy(() -> new OutboxEvent(ID, missing(), null, null, "{}", 0, CREATED_AT));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new OutboxEvent(
                    ID, OutboxDestination.FILE_PROCESSING, null, null, "{}", 0, missing()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new OutboxEvent(
                    ID, OutboxDestination.FILE_PROCESSING, null, null, "{}", -1, CREATED_AT));
  }

  private static OutboxEvent event(@Nullable String group, @Nullable Long orderingKey) {
    return new OutboxEvent(
        ID, OutboxDestination.FILE_PROCESSING, group, orderingKey, "{}", 0, CREATED_AT);
  }

  private SqlParameterSource queried(String statement) {
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).query(eq(statement), parameters.capture(), anyRowMapper());
    return parameters.getValue();
  }

  private SqlParameterSource updated(String statement) {
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(eq(statement), parameters.capture());
    return parameters.getValue();
  }

  @SuppressWarnings("unchecked")
  private RowMapper<OutboxEvent> capturedRowMapper() {
    ArgumentCaptor<RowMapper<OutboxEvent>> mapper = ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc).query(any(String.class), any(SqlParameterSource.class), mapper.capture());
    return mapper.getValue();
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<OutboxEvent> anyRowMapper() {
    return any(RowMapper.class);
  }

  private SqlParameterSource bound() {
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(eq(JdbcOutboxEventStore.INSERT), parameters.capture());
    return parameters.getValue();
  }

  @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
  private static <T> T missing() {
    return null;
  }
}
