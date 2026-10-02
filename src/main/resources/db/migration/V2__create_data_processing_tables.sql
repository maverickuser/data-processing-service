-- Operational processing records (LLD sections 17.5 and 22.2).
-- Business tables carry source_request_id without a foreign key into this schema, so cleaning up
-- old processing records can never block or cascade into accepted securities data.

CREATE TABLE data_processing.ingestion_requests (
  id                        UUID PRIMARY KEY,
  idempotency_key           TEXT NOT NULL UNIQUE,
  payload_hash              TEXT NOT NULL,
  dataset_urn               TEXT NOT NULL,
  subject                   TEXT NOT NULL,
  ordering_group            TEXT NOT NULL,
  acceptance_sequence       BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
  event_source              TEXT NOT NULL,
  event_id                  TEXT NOT NULL,
  run_id                    TEXT NOT NULL,
  inputs                    JSONB NOT NULL,
  manifest_bucket           TEXT NOT NULL,
  manifest_key              TEXT NOT NULL,
  manifest_version_id       TEXT,
  dataset_fingerprint       TEXT NOT NULL,
  submission_event          JSONB NOT NULL,
  source_contract_id        TEXT NOT NULL,
  source_contract_version   TEXT NOT NULL,
  source_contract_hash      TEXT NOT NULL,
  mapping_contract_id       TEXT NOT NULL,
  mapping_contract_version  TEXT NOT NULL,
  mapping_contract_hash     TEXT NOT NULL,
  status                    TEXT NOT NULL CHECK (status IN
    ('QUEUED', 'PROCESSING', 'RETRY_PENDING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED')),
  attempt_count             INTEGER NOT NULL DEFAULT 0,
  counts                    JSONB,
  error_count               INTEGER NOT NULL DEFAULT 0,
  submitted_at              TIMESTAMPTZ NOT NULL,
  started_at                TIMESTAMPTZ,
  completed_at              TIMESTAMPTZ,
  UNIQUE (event_source, event_id)
);

CREATE INDEX ingestion_requests_by_ordering_group
  ON data_processing.ingestion_requests (ordering_group, acceptance_sequence);

CREATE TABLE data_processing.processing_runs (
  id                    UUID PRIMARY KEY,
  ingestion_request_id  UUID NOT NULL REFERENCES data_processing.ingestion_requests (id),
  attempt_number        INTEGER NOT NULL,
  status                TEXT NOT NULL CHECK (status IN
    ('RUNNING', 'SUCCEEDED', 'FAILED_TEMPORARY', 'FAILED_PERMANENT')),
  failure_code          TEXT,
  failure_detail        TEXT,
  canonical_object_key  TEXT,
  started_at            TIMESTAMPTZ NOT NULL,
  completed_at          TIMESTAMPTZ,
  UNIQUE (ingestion_request_id, attempt_number)
);

CREATE TABLE data_processing.source_files (
  id                    UUID PRIMARY KEY,
  ingestion_request_id  UUID NOT NULL REFERENCES data_processing.ingestion_requests (id),
  fetch_job_id          TEXT,
  bucket                TEXT NOT NULL,
  object_key            TEXT NOT NULL,
  file_name             TEXT NOT NULL,
  format                TEXT NOT NULL,
  sha256                TEXT NOT NULL,
  size_bytes            BIGINT NOT NULL,
  last_modified         TIMESTAMPTZ,
  version_id            TEXT,
  UNIQUE (ingestion_request_id, bucket, object_key)
);

CREATE TABLE data_processing.rejected_records (
  id                 UUID PRIMARY KEY,
  processing_run_id  UUID NOT NULL REFERENCES data_processing.processing_runs (id) ON DELETE CASCADE,
  source_file_id     UUID REFERENCES data_processing.source_files (id),
  isin               TEXT,
  record_number      INTEGER,
  json_path          TEXT,
  disposition        TEXT NOT NULL,
  record             JSONB NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX rejected_records_by_run ON data_processing.rejected_records (processing_run_id);
CREATE INDEX rejected_records_by_created_at ON data_processing.rejected_records (created_at);

CREATE TABLE data_processing.validation_issues (
  id                  UUID PRIMARY KEY,
  processing_run_id   UUID NOT NULL REFERENCES data_processing.processing_runs (id) ON DELETE CASCADE,
  rejected_record_id  UUID REFERENCES data_processing.rejected_records (id) ON DELETE CASCADE,
  sequence_number     BIGINT NOT NULL,
  code                TEXT NOT NULL,
  isin                TEXT,
  source_file_name    TEXT,
  record_number       INTEGER,
  field               TEXT,
  json_path           TEXT,
  raw_value           JSONB,
  message             TEXT NOT NULL,
  action_taken        TEXT,
  created_at          TIMESTAMPTZ NOT NULL,
  UNIQUE (processing_run_id, sequence_number)
);

CREATE INDEX validation_issues_by_run_and_isin
  ON data_processing.validation_issues (processing_run_id, isin, sequence_number);
CREATE INDEX validation_issues_by_created_at ON data_processing.validation_issues (created_at);

CREATE TABLE data_processing.outbox_events (
  id               UUID PRIMARY KEY,
  destination      TEXT NOT NULL CHECK (destination IN ('FILE_PROCESSING', 'SECURITY_DETAILS')),
  message_group    TEXT,
  ordering_key     BIGINT,
  payload          JSONB NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('PENDING', 'DELIVERED')),
  attempt_count    INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL,
  last_error       TEXT,
  created_at       TIMESTAMPTZ NOT NULL,
  delivered_at     TIMESTAMPTZ
);

CREATE INDEX outbox_events_pending
  ON data_processing.outbox_events (status, next_attempt_at)
  WHERE status = 'PENDING';
