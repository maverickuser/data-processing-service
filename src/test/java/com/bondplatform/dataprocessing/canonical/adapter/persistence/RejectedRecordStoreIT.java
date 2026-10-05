package com.bondplatform.dataprocessing.canonical.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordBuffer;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.SourceFileRepository;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The rejected-record store against PostgreSQL (I-CSV-02, storage part). */
class RejectedRecordStoreIT extends PostgresIntegrationTest {

  private static final CanonicalRun GOLDEN = GoldenBhavcopy.RUN;
  private static final UUID RUN_ID = UUID.fromString("0190f3a0-0000-7000-8000-0000000000aa");

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private SourceFileRepository sourceFiles;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private RejectedRecordStore store;
  @Autowired private JsonRejectionStore jsonStore;

  @Test
  void storesOnlyQuarantinedRowsWithTheirIssuesLinkedToTheSourceFile() {
    CanonicalRun run = startRun();
    RejectedRecordBuffer buffer = new RejectedRecordBuffer(store, run, RUN_ID, "isin");

    GoldenBhavcopy.rows().forEach(buffer::add);
    buffer.flush();

    List<Map<String, Object>> records =
        jdbc.sql(
                """
                SELECT r.record_number, r.isin, r.disposition, r.json_path,
                  r.record ->> 'disposition' AS line_disposition, f.file_name
                FROM data_processing.rejected_records r
                LEFT JOIN data_processing.source_files f ON f.id = r.source_file_id
                WHERE r.processing_run_id = :run ORDER BY r.record_number
                """)
            .param("run", RUN_ID)
            .query()
            .listOfRows();
    assertThat(records).extracting(row -> row.get("record_number")).containsExactly(3, 4, 7, 8, 9);
    assertThat(records.get(0))
        .containsEntry("isin", "INE002B08CD2")
        .containsEntry("disposition", "QUARANTINED_SUPERSEDED")
        .containsEntry("line_disposition", "QUARANTINED_SUPERSEDED")
        .containsEntry("json_path", null)
        .containsEntry("file_name", "BSE_fgroup01012026.csv");
    assertThat(records.get(2)).containsEntry("isin", null);

    List<Map<String, Object>> issues =
        jdbc.sql(
                """
                SELECT sequence_number, code, field, raw_value #>> '{}' AS raw_value,
                  source_file_name, record_number
                FROM data_processing.validation_issues
                WHERE processing_run_id = :run ORDER BY sequence_number
                """)
            .param("run", RUN_ID)
            .query()
            .listOfRows();
    assertThat(issues).hasSize((int) buffer.issueCount());
    assertThat(issues)
        .extracting(row -> row.get("code"))
        .containsExactly(
            "DUPLICATE_ISIN_SUPERSEDED",
            "PRICE_INCONSISTENT",
            "REQUIRED_VALUE_MISSING",
            "NOT_WHOLE_NUMBER",
            "NEGATIVE_VALUE");
    assertThat(issues.get(0)).containsEntry("sequence_number", 1L).containsEntry("field", null);
    assertThat(issues.get(4))
        .containsEntry("field", "turnover")
        .containsEntry("raw_value", "-1000")
        .containsEntry("source_file_name", "BSE_fgroup01012026.csv")
        .containsEntry("record_number", 9);
  }

  @Test
  void deletingTheRunDeletesItsRecordsAndIssues() {
    CanonicalRun run = startRun();
    RejectedRecordBuffer buffer = new RejectedRecordBuffer(store, run, RUN_ID, "isin");
    GoldenBhavcopy.rows().forEach(buffer::add);
    buffer.flush();

    jdbc.sql("DELETE FROM data_processing.processing_runs WHERE id = :run")
        .param("run", RUN_ID)
        .update();

    assertThat(count("rejected_records")).isZero();
    assertThat(count("validation_issues")).isZero();
  }

  @Test
  void storesJsonRejectionsByPathWithTypedRawValuesLinkedToTheSourceFile() {
    JsonFileEvidence file =
        JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED));
    JsonCanonicalRun run = startJsonRun(file);

    long errorCount = jsonStore.saveJson(run, RUN_ID, List.of(file));

    List<Map<String, Object>> records =
        jdbc.sql(
                """
                SELECT r.json_path, r.isin, r.disposition, r.record_number, f.file_name
                FROM data_processing.rejected_records r
                LEFT JOIN data_processing.source_files f ON f.id = r.source_file_id
                WHERE r.processing_run_id = :run ORDER BY r.json_path
                """)
            .param("run", RUN_ID)
            .query()
            .listOfRows();
    assertThat(records)
        .extracting(row -> row.get("disposition"))
        .containsExactlyInAnyOrder(
            "SECTION_REJECTED", "FIELD_REJECTED", "ENTRY_FIELDS_REJECTED", "VALUE_IGNORED");
    assertThat(records)
        .allSatisfy(
            row ->
                assertThat(row)
                    .containsEntry("isin", "INE831R08076")
                    .containsEntry("record_number", null)
                    .containsEntry("file_name", file.fileName()));

    List<Map<String, Object>> issues =
        jdbc.sql(
                """
                SELECT sequence_number, code, field, json_path, raw_value::text AS raw_value,
                  action_taken, source_file_name
                FROM data_processing.validation_issues
                WHERE processing_run_id = :run ORDER BY sequence_number
                """)
            .param("run", RUN_ID)
            .query()
            .listOfRows();
    assertThat(issues).hasSize((int) errorCount);
    assertThat(issues.get(0))
        .containsEntry("code", "INVALID_TYPE")
        .containsEntry("json_path", "$.currentRatings")
        .containsEntry("raw_value", null);
    assertThat(issues.get(1))
        .containsEntry("field", "coupon_rate")
        .containsEntry("raw_value", "\"abc\"")
        .containsEntry("source_file_name", file.fileName());
    assertThat(issues.get(3))
        .containsEntry("code", "CONFLICTING_COLLATERAL_DATA")
        .containsEntry("raw_value", "100")
        .containsEntry("action_taken", "Ignored supplied coverage.");
  }

  private JsonCanonicalRun startJsonRun(JsonFileEvidence file) {
    Map<String, Object> event = SubmissionEvents.nsdl("run_201", "INE831R08076");
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    sourceFiles.saveAll(
        job,
        List.of(
            new ManifestFile(
                "isin-details",
                file.bucket(),
                file.key(),
                SourceFormat.JSON,
                "a".repeat(64),
                1024,
                null)));
    jobRuns.startRun(job, RUN_ID, 1, Instant.parse("2026-01-02T00:00:00Z"));
    return new JsonCanonicalRun(
        job,
        1,
        JsonEvidence.RUN.isin(),
        JsonEvidence.RUN.sourceContractVersion(),
        JsonEvidence.RUN.mappingContractVersion());
  }

  private CanonicalRun startRun() {
    Map<String, Object> event = SubmissionEvents.bse("run_101", "2026-01-01");
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    sourceFiles.saveAll(
        job,
        List.of(
            new ManifestFile(
                "debt-bhavcopy",
                GOLDEN.sourceBucket(),
                GOLDEN.sourceKey(),
                SourceFormat.CSV,
                "a".repeat(64),
                1024,
                null)));
    jobRuns.startRun(job, RUN_ID, GOLDEN.attemptNumber(), Instant.parse("2026-01-02T00:00:00Z"));
    return new CanonicalRun(
        job,
        GOLDEN.attemptNumber(),
        GOLDEN.tradeDate(),
        GOLDEN.sourceBucket(),
        GOLDEN.sourceKey(),
        GOLDEN.sourceContractVersion(),
        GOLDEN.mappingContractVersion());
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM data_processing." + table).query(Long.class).single();
  }
}
