package com.bondplatform.dataprocessing.job.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.IngestionRequest;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the repository sends to JDBC and how it reads a row. Whether the SQL does what it
 * should is proven against PostgreSQL in {@code IngestionRequestRepositoryIT}.
 */
class JdbcIngestionRequestRepositoryTest {

  private static final UUID ID = UUID.fromString("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
  private static final IngestionRequest STORED =
      new IngestionRequest(
          new JobId(ID),
          "run_202",
          "sha256:payload-run_202",
          "urn:bond-platform:service:data-fetch-service",
          "urn:bond-platform:submission:run_202",
          "run_202",
          new OrderingGroup("isin:INE121A07QY9"),
          7,
          JobStatus.QUEUED,
          IngestionRequests.SUBMITTED_AT);

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcIngestionRequestRepository repository =
      new JdbcIngestionRequestRepository(jdbc);

  @Test
  void bindsEveryColumnWhenInserting() {
    queryReturns(List.of(STORED));

    repository.insertIfAbsent(
        IngestionRequests.nsdl(ID, "run_202", "urn:bond-platform:submission:run_202"));

    SqlParameterSource bound = boundTo(JdbcIngestionRequestRepository.INSERT_IF_ABSENT);
    assertThat(bound.getValue("id")).isEqualTo(ID);
    assertThat(bound.getValue("idempotencyKey")).isEqualTo("run_202");
    assertThat(bound.getValue("payloadHash")).isEqualTo("sha256:payload-run_202");
    assertThat(bound.getValue("datasetUrn")).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(bound.getValue("subject")).isEqualTo("isin/INE121A07QY9");
    assertThat(bound.getValue("orderingGroup")).isEqualTo("isin:INE121A07QY9");
    assertThat(bound.getValue("eventSource"))
        .isEqualTo("urn:bond-platform:service:data-fetch-service");
    assertThat(bound.getValue("eventId")).isEqualTo("urn:bond-platform:submission:run_202");
    assertThat(bound.getValue("runId")).isEqualTo("run_202");
    assertThat(bound.getValue("inputs")).isEqualTo("{\"isin_code\": \"INE121A07QY9\"}");
    assertThat(bound.getValue("manifestBucket")).isEqualTo("data-fetch-service-artifacts");
    assertThat(bound.getValue("manifestKey")).isEqualTo("runs/run_202/manifest.json");
    assertThat(bound.hasValue("manifestVersionId")).isTrue();
    assertThat(bound.getValue("manifestVersionId")).isNull();
    assertThat(bound.getValue("datasetFingerprint")).isEqualTo("sha256:fingerprint");
    assertThat(bound.getValue("submissionEvent")).asString().contains("specversion");
    assertThat(bound.getValue("sourceContractId")).isEqualTo("nsdl-security-json");
    assertThat(bound.getValue("sourceContractVersion")).isEqualTo("v1");
    assertThat(bound.getValue("sourceContractHash")).isEqualTo("sha256:source");
    assertThat(bound.getValue("mappingContractId")).isEqualTo("nsdl-security-mapping");
    assertThat(bound.getValue("mappingContractVersion")).isEqualTo("v1");
    assertThat(bound.getValue("mappingContractHash")).isEqualTo("sha256:mapping");
    assertThat(bound.getValue("submittedAt"))
        .isEqualTo(IngestionRequests.SUBMITTED_AT.atOffset(ZoneOffset.UTC));
  }

  @Test
  void insertReturnsTheStoredRequestOrEmptyWhenOneExisted() {
    queryReturns(List.of(STORED));
    assertThat(repository.insertIfAbsent(IngestionRequests.nsdl(ID, "run_202", "event")))
        .contains(STORED);

    queryReturns(List.of());
    assertThat(repository.insertIfAbsent(IngestionRequests.nsdl(ID, "run_202", "event"))).isEmpty();
  }

  @Test
  void findsByKeyOrEventIdentity() {
    queryReturns(List.of(STORED));

    List<IngestionRequest> found =
        repository.findByIdempotencyKeyOrEvent("run_202", "source", "event-1");

    SqlParameterSource bound = boundTo(JdbcIngestionRequestRepository.FIND_BY_KEY_OR_EVENT);
    assertThat(found).containsExactly(STORED);
    assertThat(bound.getValue("idempotencyKey")).isEqualTo("run_202");
    assertThat(bound.getValue("eventSource")).isEqualTo("source");
    assertThat(bound.getValue("eventId")).isEqualTo("event-1");
  }

  @Test
  void readsEveryColumnOfRow() throws SQLException {
    queryReturns(List.of());
    repository.findByIdempotencyKeyOrEvent("run_202", "source", "event-1");
    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(ID);
    when(row.getString("idempotency_key")).thenReturn("run_202");
    when(row.getString("payload_hash")).thenReturn("sha256:payload-run_202");
    when(row.getString("event_source")).thenReturn("urn:bond-platform:service:data-fetch-service");
    when(row.getString("event_id")).thenReturn("urn:bond-platform:submission:run_202");
    when(row.getString("run_id")).thenReturn("run_202");
    when(row.getString("ordering_group")).thenReturn("isin:INE121A07QY9");
    when(row.getLong("acceptance_sequence")).thenReturn(7L);
    when(row.getString("status")).thenReturn("QUEUED");
    when(row.getObject("submitted_at", OffsetDateTime.class))
        .thenReturn(IngestionRequests.SUBMITTED_AT.atOffset(ZoneOffset.ofHoursMinutes(5, 30)));

    assertThat(capturedRowMapper().mapRow(row, 0)).isEqualTo(STORED);
  }

  @Test
  void statementsTreatEitherUniqueIdentityAsExisting() {
    assertThat(JdbcIngestionRequestRepository.INSERT_IF_ABSENT)
        .contains("data_processing.ingestion_requests", "'QUEUED'", "ON CONFLICT DO NOTHING")
        .contains("RETURNING");
    assertThat(JdbcIngestionRequestRepository.FIND_BY_KEY_OR_EVENT)
        .contains("idempotency_key = :idempotencyKey")
        .contains("event_source = :eventSource AND event_id = :eventId");
  }

  private void queryReturns(List<IngestionRequest> rows) {
    when(jdbc.query(any(String.class), any(SqlParameterSource.class), anyRowMapper()))
        .thenReturn(rows);
  }

  private SqlParameterSource boundTo(String statement) {
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).query(eq(statement), parameters.capture(), anyRowMapper());
    return parameters.getValue();
  }

  @SuppressWarnings("unchecked")
  private RowMapper<IngestionRequest> capturedRowMapper() {
    ArgumentCaptor<RowMapper<IngestionRequest>> mapper = ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc).query(any(String.class), any(SqlParameterSource.class), mapper.capture());
    return mapper.getValue();
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<IngestionRequest> anyRowMapper() {
    return any(RowMapper.class);
  }
}
