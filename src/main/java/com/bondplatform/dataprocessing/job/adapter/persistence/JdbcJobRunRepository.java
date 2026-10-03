package com.bondplatform.dataprocessing.job.adapter.persistence;

import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.job.domain.RunStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Stores job attempts in ingestion_requests and processing_runs. */
@Repository
public class JdbcJobRunRepository implements JobRunRepository {

  static final String LOCK_FOR_RUN =
      """
      SELECT id, status, attempt_count, dataset_urn, subject, ordering_group, inputs::text AS inputs,
        manifest_bucket, manifest_key, manifest_version_id, dataset_fingerprint,
        source_contract_id, source_contract_version, source_contract_hash,
        mapping_contract_id, mapping_contract_version, mapping_contract_hash
      FROM data_processing.ingestion_requests
      WHERE id = :id
      FOR UPDATE
      """;

  static final String END_ABANDONED_RUNS =
      """
      UPDATE data_processing.processing_runs
      SET status = 'FAILED_TEMPORARY', failure_code = :code, completed_at = :now
      WHERE ingestion_request_id = :id AND status = 'RUNNING'
      """;

  static final String START_JOB =
      """
      UPDATE data_processing.ingestion_requests
      SET status = 'PROCESSING', attempt_count = :attemptNumber,
        started_at = COALESCE(started_at, :now)
      WHERE id = :id
      """;

  static final String INSERT_RUN =
      """
      INSERT INTO data_processing.processing_runs
        (id, ingestion_request_id, attempt_number, status, started_at)
      VALUES (:runId, :id, :attemptNumber, 'RUNNING', :now)
      """;

  static final String COMPLETE_JOB =
      """
      UPDATE data_processing.ingestion_requests
      SET status = :status, counts = :counts::jsonb, error_count = :errorCount, completed_at = :now
      WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :attemptNumber
      """;

  static final String END_RUN =
      """
      UPDATE data_processing.processing_runs
      SET status = :runStatus, failure_code = :code, failure_detail = :detail, completed_at = :now
      WHERE id = :runId AND status = 'RUNNING'
      """;

  static final String FAIL_JOB =
      """
      UPDATE data_processing.ingestion_requests
      SET status = :jobStatus,
        completed_at = CASE WHEN :jobStatus = 'FAILED' THEN :now ELSE completed_at END
      WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :attemptNumber
      """;

  private static final RowMapper<StoredJob> ROW_MAPPER = JdbcJobRunRepository::map;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcJobRunRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StoredJob> lockForRun(JobId id) {
    return jdbc
        .query(LOCK_FOR_RUN, new MapSqlParameterSource("id", id.value()), ROW_MAPPER)
        .stream()
        .findFirst();
  }

  @Override
  public void endAbandonedRuns(JobId id, String code, Instant now) {
    jdbc.update(
        END_ABANDONED_RUNS,
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("code", code)
            .addValue("now", utc(now)));
  }

  @Override
  public void startRun(JobId id, UUID runId, int attemptNumber, Instant now) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("runId", runId)
            .addValue("attemptNumber", attemptNumber)
            .addValue("now", utc(now));
    requireOneRow(jdbc.update(START_JOB, parameters), "start", id);
    jdbc.update(INSERT_RUN, parameters);
  }

  @Override
  public void completeRun(
      JobId id, UUID runId, int attemptNumber, JobOutcome outcome, Instant now) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("runId", runId)
            .addValue("attemptNumber", attemptNumber)
            .addValue("status", outcome.status().name())
            .addValue("counts", outcome.countsJson())
            .addValue("errorCount", outcome.errorCount())
            .addValue("runStatus", RunStatus.SUCCEEDED.name())
            .addValue("code", null)
            .addValue("detail", null)
            .addValue("now", utc(now));
    // The run first: once another attempt has taken over, this one is no longer running.
    requireOneRow(jdbc.update(END_RUN, parameters), "complete", id);
    requireOneRow(jdbc.update(COMPLETE_JOB, parameters), "complete", id);
  }

  @Override
  public void failRun(
      JobId id,
      UUID runId,
      int attemptNumber,
      RunStatus runStatus,
      String code,
      String detail,
      JobStatus jobStatus,
      Instant now) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("runId", runId)
            .addValue("attemptNumber", attemptNumber)
            .addValue("runStatus", runStatus.name())
            .addValue("code", code)
            .addValue("detail", detail)
            .addValue("jobStatus", jobStatus.name())
            .addValue("now", utc(now));
    // A stale attempt, whose run another attempt has already ended, leaves the job alone.
    if (jdbc.update(END_RUN, parameters) == 1) {
      jdbc.update(FAIL_JOB, parameters);
    }
  }

  private static void requireOneRow(int rows, String action, JobId id) {
    if (rows != 1) {
      throw new IllegalStateException(
          "Could not " + action + " job " + id + ": it is not running this attempt");
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static StoredJob map(ResultSet row, int rowNumber) throws SQLException {
    return new StoredJob(
        new JobId(row.getObject("id", UUID.class)),
        JobStatus.valueOf(row.getString("status")),
        row.getInt("attempt_count"),
        new DatasetUrn(row.getString("dataset_urn")),
        row.getString("subject"),
        new OrderingGroup(row.getString("ordering_group")),
        row.getString("inputs"),
        new ManifestLocation(
            row.getString("manifest_bucket"),
            row.getString("manifest_key"),
            row.getString("manifest_version_id")),
        row.getString("dataset_fingerprint"),
        new PinnedContractVersions(
            new ContractId(
                row.getString("source_contract_id"), row.getString("source_contract_version")),
            row.getString("source_contract_hash"),
            new ContractId(
                row.getString("mapping_contract_id"), row.getString("mapping_contract_version")),
            row.getString("mapping_contract_hash")));
  }
}
