-- One login role per function, each allowed only the statements its own code runs (LLD section
-- 23.5). Migrations run as the master user, which owns every table; no function gets DDL, the
-- migration history, or a table it does not use. A later migration that adds a table grants it
-- here too, to the roles that need it; there are no default privileges.
--
-- In AWS the roles log in with RDS IAM authentication tokens: granting rds_iam disables password
-- login for them. A database without that role, such as the test container, keeps password login;
-- the roles get no password here, so outside RDS they cannot log in until one is set. Only these
-- fixed roles ever get rds_iam, so the master user, which the migrations log in as, never does.
DO $$
DECLARE
  function_role TEXT;
BEGIN
  FOREACH function_role IN ARRAY ARRAY[
    'processing_reader', 'processing_submission', 'processing_worker',
    'processing_sweeper', 'processing_retention'
  ] LOOP
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = function_role) THEN
      EXECUTE format('CREATE ROLE %I LOGIN', function_role);
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'rds_iam') THEN
      EXECUTE format('GRANT rds_iam TO %I', function_role);
    END IF;
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA data_processing TO
  processing_reader, processing_submission, processing_worker,
  processing_sweeper, processing_retention;
GRANT USAGE ON SCHEMA securities_data TO processing_reader, processing_worker;

-- Public read API: reads only what the read routes show.
GRANT SELECT ON
  securities_data.securities,
  securities_data.security_daily_market_summaries,
  securities_data.security_cash_flows,
  securities_data.security_listings,
  securities_data.security_ratings,
  securities_data.security_collateral_assets,
  data_processing.ingestion_requests,
  data_processing.processing_runs,
  data_processing.source_files,
  data_processing.validation_issues
  TO processing_reader;

-- Submission API: admits a request with its dispatch event, then delivers the event.
GRANT SELECT, INSERT ON data_processing.ingestion_requests TO processing_submission;
GRANT SELECT, INSERT, UPDATE ON data_processing.outbox_events TO processing_submission;

-- Queue worker: runs a job and publishes its results.
GRANT SELECT, UPDATE ON data_processing.ingestion_requests TO processing_worker;
GRANT SELECT, INSERT, UPDATE ON data_processing.processing_runs TO processing_worker;
GRANT SELECT, INSERT ON data_processing.source_files TO processing_worker;
GRANT INSERT ON data_processing.rejected_records, data_processing.validation_issues
  TO processing_worker;
GRANT SELECT, INSERT, UPDATE ON data_processing.outbox_events TO processing_worker;
GRANT SELECT, INSERT, UPDATE ON securities_data.securities,
  securities_data.security_daily_market_summaries TO processing_worker;
-- SELECT because an ON CONFLICT target reads the columns it names.
GRANT SELECT, INSERT ON
  securities_data.security_cash_flows,
  securities_data.security_listings,
  securities_data.security_ratings,
  securities_data.security_collateral_assets
  TO processing_worker;

-- Outbox sweeper: fails stuck jobs and delivers events that are due.
GRANT SELECT, UPDATE ON data_processing.ingestion_requests, data_processing.processing_runs,
  data_processing.outbox_events TO processing_sweeper;

-- Retention: deletes what has outlived its retention period.
GRANT SELECT, DELETE ON data_processing.validation_issues, data_processing.rejected_records,
  data_processing.outbox_events TO processing_retention;
