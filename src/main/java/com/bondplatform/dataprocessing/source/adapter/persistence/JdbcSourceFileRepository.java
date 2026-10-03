package com.bondplatform.dataprocessing.source.adapter.persistence;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import com.bondplatform.dataprocessing.source.application.SourceFileRepository;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Stores listed source files in {@code data_processing.source_files}. */
@Repository
public class JdbcSourceFileRepository implements SourceFileRepository {

  static final String INSERT_IF_ABSENT =
      """
      INSERT INTO data_processing.source_files
        (id, ingestion_request_id, fetch_job_id, bucket, object_key, file_name, format, sha256,
         size_bytes)
      VALUES
        (:id, :jobId, :fetchJobId, :bucket, :key, :fileName, :format, :sha256, :sizeBytes)
      ON CONFLICT (ingestion_request_id, bucket, object_key) DO NOTHING
      """;

  private final NamedParameterJdbcOperations jdbc;
  private final IdSupplier idSupplier;

  /** Creates the repository. */
  public JdbcSourceFileRepository(NamedParameterJdbcOperations jdbc, IdSupplier idSupplier) {
    this.jdbc = jdbc;
    this.idSupplier = idSupplier;
  }

  @Override
  public void saveAll(JobId jobId, List<ManifestFile> files) {
    if (files.isEmpty()) {
      return;
    }
    SqlParameterSource[] rows =
        files.stream()
            .map(
                file ->
                    new MapSqlParameterSource()
                        .addValue("id", idSupplier.nextId())
                        .addValue("jobId", jobId.value())
                        .addValue("fetchJobId", file.fetchJobId())
                        .addValue("bucket", file.bucket())
                        .addValue("key", file.key())
                        .addValue("fileName", file.fileName())
                        .addValue("format", file.format().name())
                        .addValue("sha256", file.sha256())
                        .addValue("sizeBytes", file.sizeBytes()))
            .toArray(SqlParameterSource[]::new);
    jdbc.batchUpdate(INSERT_IF_ABSENT, rows);
  }
}
