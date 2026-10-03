package com.bondplatform.dataprocessing.job.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the repository sends to JDBC and how it reads a row. Whether the SQL does what it
 * should is proven against PostgreSQL in {@code JobRunIT}.
 */
class JdbcJobRunRepositoryTest {

  private static final UUID ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final UUID RUN = UUID.fromString("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
  private static final Instant NOW = Instant.parse("2026-09-27T14:40:00Z");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcJobRunRepository repository = new JdbcJobRunRepository(jdbc);

  @Test
  void locksTheJobRowAndReadsEveryColumn() throws SQLException {
    when(jdbc.query(
            eq(JdbcJobRunRepository.LOCK_FOR_RUN), any(SqlParameterSource.class), anyMapper()))
        .thenReturn(List.of());

    assertThat(repository.lockForRun(new JobId(ID))).isEmpty();

    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<RowMapper<StoredJob>> mapper = ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc)
        .query(eq(JdbcJobRunRepository.LOCK_FOR_RUN), parameters.capture(), mapper.capture());
    assertThat(parameters.getValue().getValue("id")).isEqualTo(ID);
    assertThat(JdbcJobRunRepository.LOCK_FOR_RUN).contains("FOR UPDATE", "WHERE id = :id");

    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(ID);
    when(row.getString("status")).thenReturn("RETRY_PENDING");
    when(row.getInt("attempt_count")).thenReturn(1);
    when(row.getString("dataset_urn")).thenReturn("urn:bond-platform:dataset:nsdl-security");
    when(row.getString("subject")).thenReturn("isin/INE121A07QY9");
    when(row.getString("ordering_group")).thenReturn("isin:INE121A07QY9");
    when(row.getString("inputs")).thenReturn("{\"isin_code\": \"INE121A07QY9\"}");
    when(row.getString("manifest_bucket")).thenReturn("bucket");
    when(row.getString("manifest_key")).thenReturn("runs/run_202/manifest.json");
    when(row.getString("manifest_version_id")).thenReturn("v7");
    when(row.getString("dataset_fingerprint")).thenReturn("sha256:fingerprint");
    when(row.getString("source_contract_id")).thenReturn("nsdl-security-json");
    when(row.getString("source_contract_version")).thenReturn("v1");
    when(row.getString("source_contract_hash")).thenReturn("sha256:s");
    when(row.getString("mapping_contract_id")).thenReturn("nsdl-security-mapping");
    when(row.getString("mapping_contract_version")).thenReturn("v2");
    when(row.getString("mapping_contract_hash")).thenReturn("sha256:m");

    StoredJob job = mapper.getValue().mapRow(row, 0);

    assertThat(job.id().value()).isEqualTo(ID);
    assertThat(job.status()).isEqualTo(JobStatus.RETRY_PENDING);
    assertThat(job.attemptCount()).isEqualTo(1);
    assertThat(job.dataset().value()).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(job.orderingGroup().value()).isEqualTo("isin:INE121A07QY9");
    assertThat(job.manifest().versionId()).isEqualTo("v7");
    assertThat(job.contracts().mapping().name()).isEqualTo("nsdl-security-mapping-v2");
    assertThat(job.contracts().sourceHash()).isEqualTo("sha256:s");
  }

  @Test
  void endsOnlyRunningRunsOfTheJob() {
    repository.endAbandonedRuns(new JobId(ID), "ATTEMPT_ABANDONED", NOW);

    SqlParameterSource bound = updated(JdbcJobRunRepository.END_ABANDONED_RUNS);
    assertThat(bound.getValue("code")).isEqualTo("ATTEMPT_ABANDONED");
    assertThat(bound.getValue("now")).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    assertThat(JdbcJobRunRepository.END_ABANDONED_RUNS)
        .contains("status = 'RUNNING'", "'FAILED_TEMPORARY'");
  }

  @Test
  void startsTheJobThenRecordsTheRun() {
    when(jdbc.update(eq(JdbcJobRunRepository.START_JOB), any(SqlParameterSource.class)))
        .thenReturn(1);

    repository.startRun(new JobId(ID), RUN, 2, NOW);

    InOrder order = inOrder(jdbc);
    order.verify(jdbc).update(eq(JdbcJobRunRepository.START_JOB), any(SqlParameterSource.class));
    order.verify(jdbc).update(eq(JdbcJobRunRepository.INSERT_RUN), any(SqlParameterSource.class));
    SqlParameterSource bound = updated(JdbcJobRunRepository.INSERT_RUN);
    assertThat(bound.getValue("runId")).isEqualTo(RUN);
    assertThat(bound.getValue("attemptNumber")).isEqualTo(2);
    assertThat(JdbcJobRunRepository.START_JOB).contains("COALESCE(started_at, :now)");
  }

  @Test
  void startOfJobThatNoLongerExistsIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> repository.startRun(new JobId(ID), RUN, 1, NOW))
        .withMessageContaining("start");
    verify(jdbc, never())
        .update(eq(JdbcJobRunRepository.INSERT_RUN), any(SqlParameterSource.class));
  }

  @Test
  void completesJobOnlyWhileThisAttemptIsRunning() {
    when(jdbc.update(eq(JdbcJobRunRepository.END_RUN), any(SqlParameterSource.class)))
        .thenReturn(1);
    when(jdbc.update(eq(JdbcJobRunRepository.COMPLETE_JOB), any(SqlParameterSource.class)))
        .thenReturn(1);

    repository.completeRun(
        new JobId(ID),
        RUN,
        3,
        new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, "{\"a\":1}", 2),
        NOW);

    InOrder order = inOrder(jdbc);
    order.verify(jdbc).update(eq(JdbcJobRunRepository.END_RUN), any(SqlParameterSource.class));
    order.verify(jdbc).update(eq(JdbcJobRunRepository.COMPLETE_JOB), any(SqlParameterSource.class));

    SqlParameterSource bound = updated(JdbcJobRunRepository.COMPLETE_JOB);
    assertThat(bound.getValue("status")).isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(bound.getValue("counts")).isEqualTo("{\"a\":1}");
    assertThat(bound.getValue("errorCount")).isEqualTo(2);
    assertThat(bound.getValue("attemptNumber")).isEqualTo(3);
    assertThat(updated(JdbcJobRunRepository.END_RUN).getValue("runStatus")).isEqualTo("SUCCEEDED");
    assertThat(JdbcJobRunRepository.COMPLETE_JOB)
        .contains("status = 'PROCESSING'", "attempt_count = :attemptNumber");
    assertThat(JdbcJobRunRepository.END_RUN).contains("id = :runId AND status = 'RUNNING'");
  }

  @Test
  void completionOfAttemptThatIsNotRunningIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                repository.completeRun(
                    new JobId(ID), RUN, 1, new JobOutcome(JobStatus.COMPLETED, "{}", 0), NOW))
        .withMessageContaining("not running this attempt");
    verify(jdbc, never())
        .update(eq(JdbcJobRunRepository.COMPLETE_JOB), any(SqlParameterSource.class));
  }

  @Test
  void failureEndsTheRunAndSetsTheJobStatus() {
    when(jdbc.update(eq(JdbcJobRunRepository.END_RUN), any(SqlParameterSource.class)))
        .thenReturn(1);

    repository.failRun(
        new JobId(ID),
        RUN,
        2,
        RunStatus.FAILED_PERMANENT,
        "SOURCE_NOT_FOUND",
        "missing",
        JobStatus.FAILED,
        NOW);

    SqlParameterSource run = updated(JdbcJobRunRepository.END_RUN);
    assertThat(run.getValue("runStatus")).isEqualTo("FAILED_PERMANENT");
    assertThat(run.getValue("code")).isEqualTo("SOURCE_NOT_FOUND");
    assertThat(run.getValue("detail")).isEqualTo("missing");
    SqlParameterSource job = updated(JdbcJobRunRepository.FAIL_JOB);
    assertThat(job.getValue("jobStatus")).isEqualTo("FAILED");
    assertThat(job.getValue("attemptNumber")).isEqualTo(2);
    assertThat(JdbcJobRunRepository.FAIL_JOB)
        .contains("WHEN :jobStatus = 'FAILED' THEN :now", "status = 'PROCESSING'")
        .contains("attempt_count = :attemptNumber");
  }

  @Test
  void failureOfStaleAttemptLeavesTheJobAlone() {
    repository.failRun(
        new JobId(ID),
        RUN,
        1,
        RunStatus.FAILED_TEMPORARY,
        "UNEXPECTED_FAILURE",
        "java.lang.IllegalStateException",
        JobStatus.RETRY_PENDING,
        NOW);

    verify(jdbc).update(eq(JdbcJobRunRepository.END_RUN), any(SqlParameterSource.class));
    verify(jdbc, never()).update(eq(JdbcJobRunRepository.FAIL_JOB), any(SqlParameterSource.class));
  }

  private SqlParameterSource updated(String statement) {
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(eq(statement), parameters.capture());
    return parameters.getValue();
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<StoredJob> anyMapper() {
    return any(RowMapper.class);
  }
}
