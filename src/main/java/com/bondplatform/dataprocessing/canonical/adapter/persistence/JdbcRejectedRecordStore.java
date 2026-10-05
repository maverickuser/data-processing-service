package com.bondplatform.dataprocessing.canonical.adapter.persistence;

import com.bondplatform.dataprocessing.canonical.adapter.json.CanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.adapter.json.JsonCanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonRejection;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores quarantined rows in {@code data_processing.rejected_records} and their issues in {@code
 * data_processing.validation_issues}, two round trips per batch.
 *
 * <p>Each rejected record keeps its full canonical line, raw values included; each issue keeps the
 * raw value of its field as a JSON string. A JSON request's rejected parts are stored the same way,
 * located by JSONPath instead of record number. The source file is linked when the run's job listed
 * it.
 */
@Repository
public class JdbcRejectedRecordStore implements RejectedRecordStore, JsonRejectionStore {

  static final String INSERT_RECORD =
      """
      INSERT INTO data_processing.rejected_records
        (id, processing_run_id, source_file_id, isin, record_number, disposition, record,
         created_at)
      VALUES
        (:id, :processingRunId,
         (SELECT id FROM data_processing.source_files
          WHERE ingestion_request_id = :jobId AND bucket = :bucket AND object_key = :key),
         :isin, :recordNumber, :disposition, CAST(:record AS JSONB), :createdAt)
      """;

  static final String INSERT_ISSUE =
      """
      INSERT INTO data_processing.validation_issues
        (id, processing_run_id, rejected_record_id, sequence_number, code, isin, source_file_name,
         record_number, field, raw_value, message, created_at)
      VALUES
        (:id, :processingRunId, :rejectedRecordId, :sequenceNumber, :code, :isin, :sourceFileName,
         :recordNumber, :field, CAST(:rawValue AS JSONB), :message, :createdAt)
      """;

  static final String INSERT_JSON_RECORD =
      """
      INSERT INTO data_processing.rejected_records
        (id, processing_run_id, source_file_id, isin, json_path, disposition, record, created_at)
      VALUES
        (:id, :processingRunId,
         (SELECT id FROM data_processing.source_files
          WHERE ingestion_request_id = :jobId AND bucket = :bucket AND object_key = :key),
         :isin, :jsonPath, :disposition, CAST(:record AS JSONB), :createdAt)
      """;

  static final String INSERT_JSON_ISSUE =
      """
      INSERT INTO data_processing.validation_issues
        (id, processing_run_id, rejected_record_id, sequence_number, code, isin, source_file_name,
         field, json_path, raw_value, message, action_taken, created_at)
      VALUES
        (:id, :processingRunId, :rejectedRecordId, :sequenceNumber, :code, :isin, :sourceFileName,
         :field, :jsonPath, CAST(:rawValue AS JSONB), :message, :actionTaken, :createdAt)
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final NamedParameterJdbcOperations jdbc;
  private final IdSupplier idSupplier;
  private final Clock clock;

  /** Creates the store. */
  public JdbcRejectedRecordStore(
      NamedParameterJdbcOperations jdbc, IdSupplier idSupplier, Clock clock) {
    this.jdbc = jdbc;
    this.idSupplier = idSupplier;
    this.clock = clock;
  }

  @Override
  public void saveAll(CanonicalRun run, UUID processingRunId, List<RejectedRow> rows) {
    if (rows.isEmpty()) {
      return;
    }
    OffsetDateTime createdAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    String sourceFileName = run.sourceKey().substring(run.sourceKey().lastIndexOf('/') + 1);
    List<SqlParameterSource> records = new ArrayList<>();
    List<SqlParameterSource> issues = new ArrayList<>();
    for (RejectedRow row : rows) {
      UUID recordId = idSupplier.nextId();
      int recordNumber = Math.toIntExact(row.row().record().recordNumber());
      records.add(
          new MapSqlParameterSource()
              .addValue("id", recordId)
              .addValue("processingRunId", processingRunId)
              .addValue("jobId", run.jobId().value())
              .addValue("bucket", run.sourceBucket())
              .addValue("key", run.sourceKey())
              .addValue("isin", row.isin(), Types.VARCHAR)
              .addValue("recordNumber", recordNumber)
              .addValue("disposition", row.row().disposition().name())
              .addValue("record", CanonicalLineWriter.line(run, row.row()))
              .addValue("createdAt", createdAt));
      for (RejectedRow.ReviewIssue issue : row.issues()) {
        CanonicalField field = issue.field();
        issues.add(
            new MapSqlParameterSource()
                .addValue("id", idSupplier.nextId())
                .addValue("processingRunId", processingRunId)
                .addValue("rejectedRecordId", recordId)
                .addValue("sequenceNumber", issue.sequenceNumber())
                .addValue("code", issue.issue().code().name())
                .addValue("isin", row.isin(), Types.VARCHAR)
                .addValue("sourceFileName", sourceFileName)
                .addValue("recordNumber", recordNumber)
                .addValue("field", field == null ? null : field.name(), Types.VARCHAR)
                .addValue("rawValue", rawValueOf(field), Types.VARCHAR)
                .addValue("message", issue.issue().message())
                .addValue("createdAt", createdAt));
      }
    }
    jdbc.batchUpdate(INSERT_RECORD, records.toArray(SqlParameterSource[]::new));
    jdbc.batchUpdate(INSERT_ISSUE, issues.toArray(SqlParameterSource[]::new));
  }

  /**
   * {@inheritDoc}
   *
   * <p>A request's JSON files are bounded in total size, so all its rejections are written in one
   * batch of records and one of issues. An issue's raw value is kept as JSON with its type; a
   * missing value, or an object or array, has none.
   */
  @Override
  public long saveJson(JsonCanonicalRun run, UUID processingRunId, List<JsonFileEvidence> files) {
    OffsetDateTime createdAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    List<SqlParameterSource> records = new ArrayList<>();
    List<SqlParameterSource> issues = new ArrayList<>();
    long sequenceNumber = 0;
    for (JsonFileEvidence file : files) {
      for (JsonRejection rejection : file.rejections()) {
        UUID recordId = idSupplier.nextId();
        records.add(
            new MapSqlParameterSource()
                .addValue("id", recordId)
                .addValue("processingRunId", processingRunId)
                .addValue("jobId", run.jobId().value())
                .addValue("bucket", file.bucket())
                .addValue("key", file.key())
                .addValue("isin", run.isin().value())
                .addValue("jsonPath", rejection.path())
                .addValue("disposition", rejection.disposition().name())
                .addValue("record", JsonCanonicalLineWriter.record(rejection))
                .addValue("createdAt", createdAt));
        for (JsonRejection.Issue issue : rejection.issues()) {
          issues.add(
              new MapSqlParameterSource()
                  .addValue("id", idSupplier.nextId())
                  .addValue("processingRunId", processingRunId)
                  .addValue("rejectedRecordId", recordId)
                  .addValue("sequenceNumber", ++sequenceNumber)
                  .addValue("code", issue.issue().code().name())
                  .addValue("isin", run.isin().value())
                  .addValue("sourceFileName", file.fileName())
                  .addValue("field", issue.field(), Types.VARCHAR)
                  .addValue("jsonPath", issue.path())
                  .addValue(
                      "rawValue",
                      JsonCanonicalLineWriter.rawValue(issue.rawValue()).orElse(null),
                      Types.VARCHAR)
                  .addValue("message", issue.issue().message())
                  .addValue("actionTaken", issue.actionTaken(), Types.VARCHAR)
                  .addValue("createdAt", createdAt));
        }
      }
    }
    if (!records.isEmpty()) {
      jdbc.batchUpdate(INSERT_JSON_RECORD, records.toArray(SqlParameterSource[]::new));
      jdbc.batchUpdate(INSERT_JSON_ISSUE, issues.toArray(SqlParameterSource[]::new));
    }
    return sequenceNumber;
  }

  private static @Nullable String rawValueOf(@Nullable CanonicalField field) {
    return field == null ? null : JSON.writeValueAsString(field.rawValue());
  }
}
