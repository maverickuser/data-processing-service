package com.bondplatform.dataprocessing.source.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.SourceFileRepository;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The source-file repository against PostgreSQL. */
class SourceFileRepositoryIT extends PostgresIntegrationTest {

  private static final ManifestFile DETAILS =
      new ManifestFile(
          "isin-details",
          "data-fetch-service-artifacts",
          "runs/run_202/raw/isin-details/1/INE121A07QY9_isin-details.json",
          SourceFormat.JSON,
          "a".repeat(64),
          1234,
          "https://example.invalid");
  private static final ManifestFile RATINGS =
      new ManifestFile(
          null,
          "data-fetch-service-artifacts",
          "runs/run_202/raw/ratings/1/INE121A07QY9_ratings.json",
          SourceFormat.JSON,
          "b".repeat(64),
          77,
          null);

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private SourceFileRepository sourceFiles;

  @Test
  void storesEveryFileWithEveryColumn() {
    JobId job = admit();

    sourceFiles.saveAll(job, List.of(DETAILS, RATINGS));

    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT fetch_job_id, bucket, object_key, file_name, format, sha256, size_bytes,
                  last_modified, version_id
                FROM data_processing.source_files
                WHERE ingestion_request_id = :job ORDER BY object_key
                """)
            .param("job", job.value())
            .query()
            .listOfRows();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("fetch_job_id", "isin-details")
        .containsEntry("bucket", "data-fetch-service-artifacts")
        .containsEntry("object_key", DETAILS.key())
        .containsEntry("file_name", "INE121A07QY9_isin-details.json")
        .containsEntry("format", "JSON")
        .containsEntry("sha256", "a".repeat(64))
        .containsEntry("size_bytes", 1234L)
        .containsEntry("last_modified", null)
        .containsEntry("version_id", null);
    assertThat(rows.get(1)).containsEntry("fetch_job_id", null).containsEntry("size_bytes", 77L);
  }

  @Test
  void retriedAttemptStoresNothingTwice() {
    JobId job = admit();
    sourceFiles.saveAll(job, List.of(DETAILS));

    sourceFiles.saveAll(job, List.of(DETAILS, RATINGS));

    assertThat(
            jdbc.sql("SELECT count(*) FROM data_processing.source_files")
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  private JobId admit() {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
  }
}
