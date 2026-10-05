package com.bondplatform.dataprocessing.review.adapter.persistence;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.application.JobReviewRepository;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.RawValuePreview;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads jobs from {@code data_processing.ingestion_requests} and their final attempt's errors from
 * {@code processing_runs} and {@code validation_issues}.
 *
 * <p>A run's failure is shown as the error with sequence 0, so it comes first; its error ID is the
 * run's ID, and its source file is the job's only listed file, if it lists exactly one. An issue's
 * raw value is read with its JSON type, an object or array as JSON text.
 */
@Repository
public class JdbcJobReviewRepository implements JobReviewRepository {

  static final String FIND =
      """
      SELECT id, dataset_urn, status, subject, attempt_count, submitted_at, started_at,
             completed_at, counts::text AS counts
      FROM data_processing.ingestion_requests
      WHERE id = :jobId
      """;

  /**
   * The final run is the one with the highest attempt number; both queries start from it.
   *
   * <p>The count is the larger of the job's stored error count and its final run's stored issues,
   * plus the run's own failure. The stored count outlives the one-year cleanup of issues (LLD
   * section 21.1); a failed job stores none, so its issues are counted (PR 36 review AM-1).
   */
  static final String ERROR_COUNT =
      """
      WITH final_run AS (
        SELECT id, failure_code, failure_detail
        FROM data_processing.processing_runs
        WHERE ingestion_request_id = :jobId
        ORDER BY attempt_number DESC
        LIMIT 1
      )
      SELECT GREATEST(
               (SELECT error_count FROM data_processing.ingestion_requests WHERE id = :jobId),
               (SELECT count(*) FROM data_processing.validation_issues i
                JOIN final_run r ON i.processing_run_id = r.id))
           + (SELECT count(*) FROM final_run WHERE failure_code IS NOT NULL)
      """;

  static final String ERRORS =
      """
      WITH final_run AS (
        SELECT id, failure_code, failure_detail
        FROM data_processing.processing_runs
        WHERE ingestion_request_id = :jobId
        ORDER BY attempt_number DESC
        LIMIT 1
      )
      SELECT * FROM (
        SELECT r.id::text AS error_id, r.failure_code AS code, NULL::text AS isin,
               (SELECT CASE WHEN count(*) = 1 THEN min(f.file_name) END
                FROM data_processing.source_files f
                WHERE f.ingestion_request_id = :jobId) AS source_file,
               NULL::integer AS record_number, NULL::text AS field, NULL::text AS json_path,
               NULL::text AS raw_type, NULL::text AS raw_text,
               COALESCE(r.failure_detail, r.failure_code) AS message,
               NULL::text AS action_taken, 0::bigint AS sequence_number
        FROM final_run r
        WHERE r.failure_code IS NOT NULL
        UNION ALL
        SELECT i.id::text, i.code, i.isin, i.source_file_name, i.record_number, i.field,
               i.json_path, jsonb_typeof(i.raw_value),
               CASE WHEN jsonb_typeof(i.raw_value) IN ('object', 'array')
                    THEN i.raw_value::text ELSE i.raw_value #>> '{}' END,
               i.message, i.action_taken, i.sequence_number
        FROM data_processing.validation_issues i
        JOIN final_run r ON i.processing_run_id = r.id
      ) errors
      WHERE sequence_number > :after
        AND (CAST(:isin AS TEXT) IS NULL OR isin = CAST(:isin AS TEXT))
      ORDER BY sequence_number
      LIMIT :limit
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcJobReviewRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StoredJob> find(JobId jobId) {
    return jdbc.query(FIND, byJob(jobId), JdbcJobReviewRepository::job).stream().findFirst();
  }

  @Override
  public long errorCount(JobId jobId) {
    return Objects.requireNonNull(jdbc.queryForObject(ERROR_COUNT, byJob(jobId), Long.class));
  }

  @Override
  public List<ListedError> errors(
      JobId jobId, @Nullable String isin, long afterSequence, int limit) {
    return jdbc.query(
        ERRORS,
        byJob(jobId)
            .addValue("isin", isin, Types.VARCHAR)
            .addValue("after", afterSequence)
            .addValue("limit", limit),
        JdbcJobReviewRepository::error);
  }

  private static MapSqlParameterSource byJob(JobId jobId) {
    return new MapSqlParameterSource("jobId", jobId.value());
  }

  private static StoredJob job(ResultSet row, int rowNumber) throws SQLException {
    return new StoredJob(
        new JobId(row.getObject("id", UUID.class)),
        row.getString("dataset_urn"),
        JobStatus.valueOf(row.getString("status")),
        row.getString("subject"),
        row.getInt("attempt_count"),
        row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
        instant(row, "started_at"),
        instant(row, "completed_at"),
        counts(row.getString("counts")));
  }

  private static ListedError error(ResultSet row, int rowNumber) throws SQLException {
    return new ListedError(
        row.getLong("sequence_number"),
        new JobErrorView(
            row.getString("error_id"),
            row.getString("code"),
            row.getString("isin"),
            row.getString("source_file"),
            row.getObject("record_number", Integer.class),
            row.getString("field"),
            row.getString("json_path"),
            RawValuePreview.of(row.getString("raw_type"), row.getString("raw_text")),
            row.getString("message"),
            row.getString("action_taken")));
  }

  private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** Returns the stored counts in their stored order; a count that is not a number is null. */
  private static @Nullable Map<String, @Nullable Long> counts(@Nullable String json) {
    if (json == null) {
      return null;
    }
    Map<String, @Nullable Long> counts = new LinkedHashMap<>();
    JsonNode node = JSON.readTree(json);
    node.properties()
        .forEach(
            entry ->
                counts.put(
                    entry.getKey(),
                    entry.getValue().isIntegralNumber() ? entry.getValue().asLong() : null));
    return counts;
  }
}
