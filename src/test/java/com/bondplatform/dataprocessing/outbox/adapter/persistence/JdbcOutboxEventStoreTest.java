package com.bondplatform.dataprocessing.outbox.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
