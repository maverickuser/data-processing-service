package com.bondplatform.dataprocessing.review.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.application.JobReviewRepository.StoredJob;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.RawValuePreview;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcJobReviewRepositoryTest {

  private static final JobId JOB = JobId.parse("0190f3a0-0000-7000-8000-000000000001");
  private static final OffsetDateTime SUBMITTED =
      OffsetDateTime.of(2026, 1, 1, 14, 30, 0, 0, ZoneOffset.UTC);

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcJobReviewRepository repository = new JdbcJobReviewRepository(jdbc);

  @Test
  void findsJobByIdAndMapsEveryColumn() throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(JOB.value());
    when(row.getString("dataset_urn")).thenReturn("urn:bond-platform:dataset:nsdl-security");
    when(row.getString("status")).thenReturn("COMPLETED");
    when(row.getString("subject")).thenReturn("isin/INE831R08076");
    when(row.getInt("attempt_count")).thenReturn(2);
    when(row.getObject("submitted_at", OffsetDateTime.class)).thenReturn(SUBMITTED);
    when(row.getObject("started_at", OffsetDateTime.class)).thenReturn(SUBMITTED.plusSeconds(5));
    when(row.getObject("completed_at", OffsetDateTime.class)).thenReturn(null);
    when(row.getString("counts"))
        .thenReturn("{\"filesListed\": 6, \"fieldsRejected\": null, \"other\": \"x\"}");

    StoredJob job = mapped(JdbcJobReviewRepository.FIND, row, StoredJob.class);

    assertThat(job.jobId()).isEqualTo(JOB);
    assertThat(job.dataset()).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.subject()).isEqualTo("isin/INE831R08076");
    assertThat(job.attemptCount()).isEqualTo(2);
    assertThat(job.submittedAt()).isEqualTo(Instant.parse("2026-01-01T14:30:00Z"));
    assertThat(job.startedAt()).isEqualTo(Instant.parse("2026-01-01T14:30:05Z"));
    assertThat(job.completedAt()).isNull();
    assertThat(Objects.requireNonNull(job.counts()))
        .containsExactly(
            Map.entry("filesListed", 6L),
            new java.util.AbstractMap.SimpleEntry<>("fieldsRejected", null),
            new java.util.AbstractMap.SimpleEntry<>("other", null));
  }

  @Test
  void jobWithoutCountsHasNone() throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(JOB.value());
    when(row.getString("status")).thenReturn("QUEUED");
    when(row.getObject("submitted_at", OffsetDateTime.class)).thenReturn(SUBMITTED);

    assertThat(mapped(JdbcJobReviewRepository.FIND, row, StoredJob.class).counts()).isNull();
  }

  @Test
  void unknownJobIsEmpty() {
    assertThat(repository.find(JOB)).isEmpty();
  }

  // AL-1 and AC-3: only the final run counts, its failure included
  @Test
  void errorCountReadsTheFinalRun() {
    when(jdbc.queryForObject(
            eq(JdbcJobReviewRepository.ERROR_COUNT), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(3L);

    assertThat(repository.errorCount(JOB)).isEqualTo(3);
    assertThat(JdbcJobReviewRepository.ERROR_COUNT)
        .contains("ORDER BY attempt_number DESC")
        .contains("failure_code IS NOT NULL");
  }

  @Test
  void errorRowIsMappedWithItsTypedRawValue() throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("error_id")).thenReturn("4b1d0000-0000-7000-8000-000000000001");
    when(row.getString("code")).thenReturn("INVALID_TYPE");
    when(row.getString("isin")).thenReturn("INE831R08076");
    when(row.getString("source_file")).thenReturn("INE831R08076_isin-details.json");
    when(row.getObject("record_number", Integer.class)).thenReturn(null);
    when(row.getString("field")).thenReturn("issuer_name");
    when(row.getString("json_path")).thenReturn("$.issuerName");
    when(row.getString("raw_type")).thenReturn("object");
    when(row.getString("raw_text")).thenReturn("{\"a\": 1}");
    when(row.getString("message")).thenReturn("Expected text.");
    when(row.getString("action_taken")).thenReturn(null);

    JobErrorView error = mapped(JdbcJobReviewRepository.FIRST_ERRORS, row, JobErrorView.class);

    assertThat(error)
        .isEqualTo(
            new JobErrorView(
                "4b1d0000-0000-7000-8000-000000000001",
                "INVALID_TYPE",
                "INE831R08076",
                "INE831R08076_isin-details.json",
                null,
                "issuer_name",
                "$.issuerName",
                new RawValuePreview("{\"a\": 1}", false),
                "Expected text.",
                null));
  }

  @Test
  void firstErrorsAskForTheLimitInSequenceOrder() {
    when(jdbc.query(
            eq(JdbcJobReviewRepository.FIRST_ERRORS),
            any(SqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of());

    repository.firstErrors(JOB, 5);

    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc)
        .query(
            eq(JdbcJobReviewRepository.FIRST_ERRORS), parameters.capture(), any(RowMapper.class));
    assertThat(parameters.getValue().getValue("jobId")).isEqualTo(JOB.value());
    assertThat(parameters.getValue().getValue("limit")).isEqualTo(5);
    assertThat(JdbcJobReviewRepository.FIRST_ERRORS)
        .contains("ORDER BY sequence_number")
        .contains("0::bigint AS sequence_number");
  }

  /** Runs the repository's query, then maps the row with the mapper it passed. */
  @SuppressWarnings("unchecked")
  private <T> T mapped(String sql, ResultSet row, Class<T> type) throws SQLException {
    ArgumentCaptor<RowMapper<T>> mapper = ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(eq(sql), any(SqlParameterSource.class), mapper.capture()))
        .thenReturn(List.of());
    if (type == StoredJob.class) {
      repository.find(JOB);
    } else {
      repository.firstErrors(JOB, 5);
    }
    return Objects.requireNonNull(mapper.getValue().mapRow(row, 0));
  }
}
