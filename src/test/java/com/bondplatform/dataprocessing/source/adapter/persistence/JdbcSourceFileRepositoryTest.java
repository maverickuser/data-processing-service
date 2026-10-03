package com.bondplatform.dataprocessing.source.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the repository sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code SourceFileRepositoryIT}.
 */
class JdbcSourceFileRepositoryTest {

  private static final JobId JOB =
      new JobId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final AtomicLong nextId = new AtomicLong(1);
  private final JdbcSourceFileRepository repository =
      new JdbcSourceFileRepository(jdbc, () -> new UUID(0, nextId.getAndIncrement()));

  @Test
  void bindsEveryColumnOfEachFileInOneBatch() {
    repository.saveAll(
        JOB,
        List.of(
            new ManifestFile(
                "isin-details",
                "data-fetch-service-artifacts",
                "runs/run_202/raw/isin-details/1/INE121A07QY9_isin-details.json",
                SourceFormat.JSON,
                "a".repeat(64),
                1234,
                "https://example.invalid"),
            new ManifestFile(null, "b", "k/x.json", SourceFormat.JSON, "c".repeat(64), 1, null)));

    ArgumentCaptor<SqlParameterSource[]> rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc).batchUpdate(eq(JdbcSourceFileRepository.INSERT_IF_ABSENT), rows.capture());
    assertThat(rows.getValue()).hasSize(2);
    SqlParameterSource first = rows.getValue()[0];
    assertThat(first.getValue("id")).isEqualTo(new UUID(0, 1));
    assertThat(first.getValue("jobId")).isEqualTo(JOB.value());
    assertThat(first.getValue("fetchJobId")).isEqualTo("isin-details");
    assertThat(first.getValue("bucket")).isEqualTo("data-fetch-service-artifacts");
    assertThat(first.getValue("key"))
        .isEqualTo("runs/run_202/raw/isin-details/1/INE121A07QY9_isin-details.json");
    assertThat(first.getValue("fileName")).isEqualTo("INE121A07QY9_isin-details.json");
    assertThat(first.getValue("format")).isEqualTo("JSON");
    assertThat(first.getValue("sha256")).isEqualTo("a".repeat(64));
    assertThat(first.getValue("sizeBytes")).isEqualTo(1234L);
    assertThat(rows.getValue()[1].getValue("fetchJobId")).isNull();
    assertThat(JdbcSourceFileRepository.INSERT_IF_ABSENT)
        .contains("ON CONFLICT (ingestion_request_id, bucket, object_key) DO NOTHING");
  }

  @Test
  void sendsNothingForNoFiles() {
    repository.saveAll(JOB, List.of());

    verifyNoInteractions(jdbc);
  }
}
