package com.bondplatform.dataprocessing.job.adapter.persistence;

import com.bondplatform.dataprocessing.job.application.IngestionRequestRepository;
import com.bondplatform.dataprocessing.job.domain.IngestionRequest;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Stores ingestion requests in {@code data_processing.ingestion_requests}. */
@Repository
public class JdbcIngestionRequestRepository implements IngestionRequestRepository {

  /** No conflict target: either unique constraint (key, or source and event ID) means "exists". */
  static final String INSERT_IF_ABSENT =
      """
      INSERT INTO data_processing.ingestion_requests
        (id, idempotency_key, payload_hash, dataset_urn, subject, ordering_group, event_source,
         event_id, run_id, inputs, manifest_bucket, manifest_key, manifest_version_id,
         dataset_fingerprint, submission_event, source_contract_id, source_contract_version,
         source_contract_hash, mapping_contract_id, mapping_contract_version,
         mapping_contract_hash, status, submitted_at)
      VALUES
        (:id, :idempotencyKey, :payloadHash, :datasetUrn, :subject, :orderingGroup, :eventSource,
         :eventId, :runId, :inputs::jsonb, :manifestBucket, :manifestKey, :manifestVersionId,
         :datasetFingerprint, :submissionEvent::jsonb, :sourceContractId, :sourceContractVersion,
         :sourceContractHash, :mappingContractId, :mappingContractVersion,
         :mappingContractHash, 'QUEUED', :submittedAt)
      ON CONFLICT DO NOTHING
      RETURNING id, idempotency_key, payload_hash, event_source, event_id, run_id, ordering_group,
        acceptance_sequence, status, submitted_at
      """;

  static final String FIND_BY_KEY_OR_EVENT =
      """
      SELECT id, idempotency_key, payload_hash, event_source, event_id, run_id, ordering_group,
        acceptance_sequence, status, submitted_at
      FROM data_processing.ingestion_requests
      WHERE idempotency_key = :idempotencyKey
         OR (event_source = :eventSource AND event_id = :eventId)
      ORDER BY acceptance_sequence
      """;

  private static final RowMapper<IngestionRequest> ROW_MAPPER = JdbcIngestionRequestRepository::map;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcIngestionRequestRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IngestionRequest> insertIfAbsent(NewIngestionRequest request) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", request.id().value())
            .addValue("idempotencyKey", request.idempotencyKey())
            .addValue("payloadHash", request.payloadHash())
            .addValue("datasetUrn", request.dataset().value())
            .addValue("subject", request.subject())
            .addValue("orderingGroup", request.orderingGroup().value())
            .addValue("eventSource", request.eventSource())
            .addValue("eventId", request.eventId())
            .addValue("runId", request.runId())
            .addValue("inputs", request.inputsJson())
            .addValue("manifestBucket", request.manifest().bucket())
            .addValue("manifestKey", request.manifest().key())
            .addValue("manifestVersionId", request.manifest().versionId())
            .addValue("datasetFingerprint", request.datasetFingerprint())
            .addValue("submissionEvent", request.submissionEventJson())
            .addValue("sourceContractId", request.contracts().source().id())
            .addValue("sourceContractVersion", request.contracts().source().version())
            .addValue("sourceContractHash", request.contracts().sourceHash())
            .addValue("mappingContractId", request.contracts().mapping().id())
            .addValue("mappingContractVersion", request.contracts().mapping().version())
            .addValue("mappingContractHash", request.contracts().mappingHash())
            .addValue("submittedAt", request.submittedAt().atOffset(ZoneOffset.UTC));
    return jdbc.query(INSERT_IF_ABSENT, parameters, ROW_MAPPER).stream().findFirst();
  }

  @Override
  public List<IngestionRequest> findByIdempotencyKeyOrEvent(
      String idempotencyKey, String eventSource, String eventId) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("idempotencyKey", idempotencyKey)
            .addValue("eventSource", eventSource)
            .addValue("eventId", eventId);
    return jdbc.query(FIND_BY_KEY_OR_EVENT, parameters, ROW_MAPPER);
  }

  private static IngestionRequest map(ResultSet row, int rowNumber) throws SQLException {
    return new IngestionRequest(
        new JobId(row.getObject("id", UUID.class)),
        row.getString("idempotency_key"),
        row.getString("payload_hash"),
        row.getString("event_source"),
        row.getString("event_id"),
        row.getString("run_id"),
        new OrderingGroup(row.getString("ordering_group")),
        row.getLong("acceptance_sequence"),
        JobStatus.valueOf(row.getString("status")),
        row.getObject("submitted_at", OffsetDateTime.class).toInstant());
  }
}
