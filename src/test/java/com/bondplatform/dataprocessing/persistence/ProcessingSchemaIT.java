package com.bondplatform.dataprocessing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test case I-DB-01 (processing part), and the constraints of the processing tables. */
class ProcessingSchemaIT extends PostgresIntegrationTest {

  private static final String INSERT_REQUEST =
      """
      INSERT INTO data_processing.ingestion_requests
        (id, idempotency_key, payload_hash, dataset_urn, subject, ordering_group, event_source,
         event_id, run_id, inputs, manifest_bucket, manifest_key, dataset_fingerprint,
         submission_event, source_contract_id, source_contract_version, source_contract_hash,
         mapping_contract_id, mapping_contract_version, mapping_contract_hash, status, submitted_at)
      VALUES
        (?, ?, 'sha256:p', 'urn:bond-platform:dataset:nsdl-security', 'isin/INE831R08076',
         'isin:INE831R08076', 'urn:bond-platform:service:data-fetch-service', ?, 'run_1', '{}',
         'bucket', 'runs/run_1/manifest.json', 'sha256:f', '{}', 'nsdl-security-json', 'v1',
         'sha256:s', 'nsdl-security-mapping', 'v1', 'sha256:m', ?, now())
      """;

  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void emptyTables() {
    jdbc.sql(
            """
            TRUNCATE data_processing.validation_issues, data_processing.rejected_records,
              data_processing.source_files, data_processing.processing_runs,
              data_processing.outbox_events, data_processing.ingestion_requests
            """)
        .update();
  }

  // I-DB-01
  @Test
  void schemaHoldsTheSixProcessingTablesAndTheMigrationHistory() {
    List<String> tables =
        jdbc.sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'data_processing' ORDER BY table_name")
            .query(String.class)
            .list();

    assertThat(tables)
        .containsExactly(
            "flyway_schema_history",
            "ingestion_requests",
            "outbox_events",
            "processing_runs",
            "rejected_records",
            "source_files",
            "validation_issues");
  }

  // I-DB-01
  @Test
  void tablesHaveExactlyTheColumnsOfTheDesign() {
    assertThat(columnsOf("ingestion_requests"))
        .containsExactly(
            "id uuid NO",
            "idempotency_key text NO",
            "payload_hash text NO",
            "dataset_urn text NO",
            "subject text NO",
            "ordering_group text NO",
            "acceptance_sequence bigint NO",
            "event_source text NO",
            "event_id text NO",
            "run_id text NO",
            "inputs jsonb NO",
            "manifest_bucket text NO",
            "manifest_key text NO",
            "manifest_version_id text YES",
            "dataset_fingerprint text NO",
            "submission_event jsonb NO",
            "source_contract_id text NO",
            "source_contract_version text NO",
            "source_contract_hash text NO",
            "mapping_contract_id text NO",
            "mapping_contract_version text NO",
            "mapping_contract_hash text NO",
            "status text NO",
            "attempt_count integer NO",
            "counts jsonb YES",
            "error_count integer NO",
            "submitted_at timestamp with time zone NO",
            "started_at timestamp with time zone YES",
            "completed_at timestamp with time zone YES");
    assertThat(columnsOf("processing_runs"))
        .containsExactly(
            "id uuid NO",
            "ingestion_request_id uuid NO",
            "attempt_number integer NO",
            "status text NO",
            "failure_code text YES",
            "failure_detail text YES",
            "canonical_object_key text YES",
            "started_at timestamp with time zone NO",
            "completed_at timestamp with time zone YES");
    assertThat(columnsOf("source_files"))
        .containsExactly(
            "id uuid NO",
            "ingestion_request_id uuid NO",
            "fetch_job_id text YES",
            "bucket text NO",
            "object_key text NO",
            "file_name text NO",
            "format text NO",
            "sha256 text NO",
            "size_bytes bigint NO",
            "last_modified timestamp with time zone YES",
            "version_id text YES");
    assertThat(columnsOf("rejected_records"))
        .containsExactly(
            "id uuid NO",
            "processing_run_id uuid NO",
            "source_file_id uuid YES",
            "isin text YES",
            "record_number integer YES",
            "json_path text YES",
            "disposition text NO",
            "record jsonb NO",
            "created_at timestamp with time zone NO");
    assertThat(columnsOf("validation_issues"))
        .containsExactly(
            "id uuid NO",
            "processing_run_id uuid NO",
            "rejected_record_id uuid YES",
            "sequence_number bigint NO",
            "code text NO",
            "isin text YES",
            "source_file_name text YES",
            "record_number integer YES",
            "field text YES",
            "json_path text YES",
            "raw_value jsonb YES",
            "message text NO",
            "action_taken text YES",
            "created_at timestamp with time zone NO");
    assertThat(columnsOf("outbox_events"))
        .containsExactly(
            "id uuid NO",
            "destination text NO",
            "message_group text YES",
            "ordering_key bigint YES",
            "payload jsonb NO",
            "status text NO",
            "attempt_count integer NO",
            "next_attempt_at timestamp with time zone NO",
            "last_error text YES",
            "created_at timestamp with time zone NO",
            "delivered_at timestamp with time zone YES");
  }

  @Test
  void acceptanceSequenceIncreasesWithEachRequest() {
    UUID first = insertRequest("key-1", "event-1", "QUEUED");
    UUID second = insertRequest("key-2", "event-2", "QUEUED");

    assertThat(sequenceOf(second)).isGreaterThan(sequenceOf(first));
  }

  @Test
  void idempotencyKeyAndEventIdentityAreUnique() {
    insertRequest("key-1", "event-1", "QUEUED");

    assertThatThrownBy(() -> insertRequest("key-1", "event-2", "QUEUED"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRequest("key-2", "event-1", "QUEUED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void statusMustBeOneOfTheAgreedValues() {
    assertThatThrownBy(() -> insertRequest("key-1", "event-1", "DONE"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void attemptNumberIsUniqueWithinRequest() {
    UUID request = insertRequest("key-1", "event-1", "PROCESSING");
    insertRun(request, 1);
    insertRun(request, 2);

    assertThatThrownBy(() -> insertRun(request, 2))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingRunRemovesItsRejectedRecordsAndIssues() {
    UUID request = insertRequest("key-1", "event-1", "PROCESSING");
    UUID run = insertRun(request, 1);
    UUID rejected = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO data_processing.rejected_records"
                + " (id, processing_run_id, disposition, record, created_at)"
                + " VALUES (?, ?, 'QUARANTINED_INVALID', '{}', now())")
        .params(rejected, run)
        .update();
    jdbc.sql(
            "INSERT INTO data_processing.validation_issues"
                + " (id, processing_run_id, rejected_record_id, sequence_number, code, message,"
                + " created_at) VALUES (?, ?, ?, 1, 'INVALID_DECIMAL', 'bad', now())")
        .params(UUID.randomUUID(), run, rejected)
        .update();

    jdbc.sql("DELETE FROM data_processing.processing_runs WHERE id = ?").param(run).update();

    assertThat(count("rejected_records")).isZero();
    assertThat(count("validation_issues")).isZero();
  }

  @Test
  void outboxDestinationAndStatusAreConstrained() {
    String insert =
        "INSERT INTO data_processing.outbox_events"
            + " (id, destination, payload, status, next_attempt_at, created_at)"
            + " VALUES (?, ?, '{}', ?, now(), now())";

    jdbc.sql(insert).params(UUID.randomUUID(), "SECURITY_DETAILS", "PENDING").update();

    assertThatThrownBy(
            () -> jdbc.sql(insert).params(UUID.randomUUID(), "EMAIL", "PENDING").update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.sql(insert).params(UUID.randomUUID(), "FILE_PROCESSING", "SENT").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID insertRequest(String idempotencyKey, String eventId, String status) {
    UUID id = UUID.randomUUID();
    jdbc.sql(INSERT_REQUEST).params(id, idempotencyKey, eventId, status).update();
    return id;
  }

  private UUID insertRun(UUID request, int attemptNumber) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO data_processing.processing_runs"
                + " (id, ingestion_request_id, attempt_number, status, started_at)"
                + " VALUES (?, ?, ?, 'RUNNING', now())")
        .params(id, request, attemptNumber)
        .update();
    return id;
  }

  private long sequenceOf(UUID request) {
    return jdbc.sql(
            "SELECT acceptance_sequence FROM data_processing.ingestion_requests WHERE id = ?")
        .param(request)
        .query(Long.class)
        .single();
  }

  private List<String> columnsOf(String table) {
    return jdbc.sql(
            "SELECT column_name || ' ' || data_type || ' ' || is_nullable"
                + " FROM information_schema.columns"
                + " WHERE table_schema = 'data_processing' AND table_name = ?"
                + " ORDER BY ordinal_position")
        .param(table)
        .query(String.class)
        .list();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM data_processing." + table).query(Long.class).single();
  }
}
