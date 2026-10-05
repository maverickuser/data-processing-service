package com.bondplatform.dataprocessing.operations.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.operations.application.StuckJobRule;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the store sends to JDBC. Which jobs the SQL finds and fails is proven against
 * PostgreSQL in {@code StuckJobIT}.
 */
class JdbcStuckJobStoreTest {

  private static final UUID JOB = new UUID(0, 7);
  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
  private static final StuckJobRule RULE =
      new StuckJobRule(
          Instant.parse("2026-10-05T11:00:00Z"), Instant.parse("2026-10-05T11:44:00Z"), 3);

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcStuckJobStore store = new JdbcStuckJobStore(jdbc);

  @Test
  void findsStuckJobsWithTheRuleAndLimit() {
    when(jdbc.queryForList(
            eq(JdbcStuckJobStore.FIND_STUCK), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(JOB));

    assertThat(store.findStuck(RULE, 25)).containsExactly(new JobId(JOB));

    SqlParameterSource params = captured(JdbcStuckJobStore.FIND_STUCK);
    assertThat(params.getValue("progressCutoff"))
        .isEqualTo(OffsetDateTime.of(2026, 10, 5, 11, 0, 0, 0, ZoneOffset.UTC));
    assertThat(params.getValue("attemptCutoff"))
        .isEqualTo(OffsetDateTime.of(2026, 10, 5, 11, 44, 0, 0, ZoneOffset.UTC));
    assertThat(params.getValue("finalAttempt")).isEqualTo(3);
    assertThat(params.getValue("limit")).isEqualTo(25);
  }

  @Test
  void endsRunningRunsAndFailsTheJobWhenItIsStillStuck() {
    when(jdbc.queryForList(
            eq(JdbcStuckJobStore.LOCK_IF_STUCK), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(JOB));

    assertThat(store.failIfStuck(new JobId(JOB), RULE, "ATTEMPT_ABANDONED", NOW)).isTrue();

    assertThat(captured(JdbcStuckJobStore.LOCK_IF_STUCK).getValue("id")).isEqualTo(JOB);
    ArgumentCaptor<SqlParameterSource> change = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(eq(JdbcStuckJobStore.END_RUNNING_RUNS), change.capture());
    assertThat(change.getValue().getValue("code")).isEqualTo("ATTEMPT_ABANDONED");
    assertThat(change.getValue().getValue("now"))
        .isEqualTo(OffsetDateTime.of(2026, 10, 5, 12, 0, 0, 0, ZoneOffset.UTC));
    verify(jdbc).update(eq(JdbcStuckJobStore.FAIL_JOB), any(SqlParameterSource.class));
  }

  @Test
  void changesNothingWhenTheJobIsNoLongerStuckOrIsHeld() {
    when(jdbc.queryForList(
            eq(JdbcStuckJobStore.LOCK_IF_STUCK), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of());

    assertThat(store.failIfStuck(new JobId(JOB), RULE, "ATTEMPT_ABANDONED", NOW)).isFalse();

    verify(jdbc, never()).update(any(String.class), any(SqlParameterSource.class));
  }

  @Test
  void skipsJobsAnotherTransactionHolds() {
    assertThat(JdbcStuckJobStore.LOCK_IF_STUCK).endsWith("FOR UPDATE SKIP LOCKED");
  }

  private SqlParameterSource captured(String sql) {
    ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).queryForList(eq(sql), params.capture(), eq(UUID.class));
    return params.getValue();
  }
}
