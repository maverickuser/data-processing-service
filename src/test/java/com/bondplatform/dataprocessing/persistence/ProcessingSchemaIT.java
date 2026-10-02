package com.bondplatform.dataprocessing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Test case I-DB-01 (processing part), and the constraints of the processing tables. */
class ProcessingSchemaIT extends PostgresIntegrationTest {

  private static final String SCHEMA = "data_processing";

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

  // I-DB-01
  @Test
  void schemaHoldsTheSixProcessingTablesAndTheMigrationHistory() {
    assertThat(schema().tables(SCHEMA))
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
            "id uuid NOT NULL",
            "idempotency_key text NOT NULL",
            "payload_hash text NOT NULL",
            "dataset_urn text NOT NULL",
            "subject text NOT NULL",
            "ordering_group text NOT NULL",
            "acceptance_sequence bigint NOT NULL IDENTITY",
            "event_source text NOT NULL",
            "event_id text NOT NULL",
            "run_id text NOT NULL",
            "inputs jsonb NOT NULL",
            "manifest_bucket text NOT NULL",
            "manifest_key text NOT NULL",
            "manifest_version_id text",
            "dataset_fingerprint text NOT NULL",
            "submission_event jsonb NOT NULL",
            "source_contract_id text NOT NULL",
            "source_contract_version text NOT NULL",
            "source_contract_hash text NOT NULL",
            "mapping_contract_id text NOT NULL",
            "mapping_contract_version text NOT NULL",
            "mapping_contract_hash text NOT NULL",
            "status text NOT NULL",
            "attempt_count integer NOT NULL DEFAULT 0",
            "counts jsonb",
            "error_count integer NOT NULL DEFAULT 0",
            "submitted_at timestamp with time zone NOT NULL",
            "started_at timestamp with time zone",
            "completed_at timestamp with time zone");
    assertThat(columnsOf("processing_runs"))
        .containsExactly(
            "id uuid NOT NULL",
            "ingestion_request_id uuid NOT NULL",
            "attempt_number integer NOT NULL",
            "status text NOT NULL",
            "failure_code text",
            "failure_detail text",
            "canonical_object_key text",
            "started_at timestamp with time zone NOT NULL",
            "completed_at timestamp with time zone");
    assertThat(columnsOf("source_files"))
        .containsExactly(
            "id uuid NOT NULL",
            "ingestion_request_id uuid NOT NULL",
            "fetch_job_id text",
            "bucket text NOT NULL",
            "object_key text NOT NULL",
            "file_name text NOT NULL",
            "format text NOT NULL",
            "sha256 text NOT NULL",
            "size_bytes bigint NOT NULL",
            "last_modified timestamp with time zone",
            "version_id text");
    assertThat(columnsOf("rejected_records"))
        .containsExactly(
            "id uuid NOT NULL",
            "processing_run_id uuid NOT NULL",
            "source_file_id uuid",
            "isin text",
            "record_number integer",
            "json_path text",
            "disposition text NOT NULL",
            "record jsonb NOT NULL",
            "created_at timestamp with time zone NOT NULL");
    assertThat(columnsOf("validation_issues"))
        .containsExactly(
            "id uuid NOT NULL",
            "processing_run_id uuid NOT NULL",
            "rejected_record_id uuid",
            "sequence_number bigint NOT NULL",
            "code text NOT NULL",
            "isin text",
            "source_file_name text",
            "record_number integer",
            "field text",
            "json_path text",
            "raw_value jsonb",
            "message text NOT NULL",
            "action_taken text",
            "created_at timestamp with time zone NOT NULL");
    assertThat(columnsOf("outbox_events"))
        .containsExactly(
            "id uuid NOT NULL",
            "destination text NOT NULL",
            "message_group text",
            "ordering_key bigint",
            "payload jsonb NOT NULL",
            "status text NOT NULL",
            "attempt_count integer NOT NULL DEFAULT 0",
            "next_attempt_at timestamp with time zone NOT NULL",
            "last_error text",
            "created_at timestamp with time zone NOT NULL",
            "delivered_at timestamp with time zone");
  }

  // I-DB-01
  @Test
  void namedIndexesMatchTheDesign() {
    assertThat(schema().indexes(SCHEMA, "ingestion_requests"))
        .contains(
            "CREATE INDEX ingestion_requests_by_ordering_group"
                + " ON data_processing.ingestion_requests"
                + " USING btree (ordering_group, acceptance_sequence)");
    assertThat(schema().indexes(SCHEMA, "rejected_records"))
        .containsExactly(
            "CREATE INDEX rejected_records_by_created_at ON data_processing.rejected_records"
                + " USING btree (created_at)",
            "CREATE INDEX rejected_records_by_run ON data_processing.rejected_records"
                + " USING btree (processing_run_id)",
            "CREATE INDEX rejected_records_by_source_file ON data_processing.rejected_records"
                + " USING btree (source_file_id)",
            "CREATE UNIQUE INDEX rejected_records_pkey ON data_processing.rejected_records"
                + " USING btree (id)");
    assertThat(schema().indexes(SCHEMA, "validation_issues"))
        .contains(
            "CREATE INDEX validation_issues_by_created_at ON data_processing.validation_issues"
                + " USING btree (created_at)",
            "CREATE INDEX validation_issues_by_rejected_record"
                + " ON data_processing.validation_issues USING btree (rejected_record_id)",
            "CREATE INDEX validation_issues_by_run_and_isin"
                + " ON data_processing.validation_issues"
                + " USING btree (processing_run_id, isin, sequence_number)");
    assertThat(schema().indexes(SCHEMA, "outbox_events"))
        .containsExactly(
            "CREATE INDEX outbox_events_pending ON data_processing.outbox_events"
                + " USING btree (status, next_attempt_at) WHERE (status = 'PENDING'::text)",
            "CREATE UNIQUE INDEX outbox_events_pkey ON data_processing.outbox_events"
                + " USING btree (id)");
  }

  // I-DB-01
  @Test
  void foreignKeysMatchTheDesign() {
    assertThat(schema().foreignKeys(SCHEMA))
        .containsExactly(
            "processing_runs.ingestion_request_id -> ingestion_requests.id ON DELETE NO ACTION",
            "rejected_records.processing_run_id -> processing_runs.id ON DELETE CASCADE",
            "rejected_records.source_file_id -> source_files.id ON DELETE NO ACTION",
            "source_files.ingestion_request_id -> ingestion_requests.id ON DELETE NO ACTION",
            "validation_issues.processing_run_id -> processing_runs.id ON DELETE CASCADE",
            "validation_issues.rejected_record_id -> rejected_records.id ON DELETE CASCADE");
  }

  @Test
  void requestWithRunsOrSourceFilesCannotBeDeleted() {
    UUID request = insertRequest("key-1", "event-1", "PROCESSING");
    insertRun(request, 1);

    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM data_processing.ingestion_requests WHERE id = ?")
                    .param(request)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
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
    return schema().columns(SCHEMA, table);
  }

  private SchemaInspector schema() {
    return new SchemaInspector(jdbc);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM data_processing." + table).query(Long.class).single();
  }
}
