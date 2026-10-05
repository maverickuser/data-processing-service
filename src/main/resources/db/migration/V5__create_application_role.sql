-- The role the functions log in as (LLD section 23.5). Migrations run as the master user, which
-- owns every table; the functions get data access only, never DDL. In AWS the role logs in with
-- RDS IAM authentication tokens: granting rds_iam disables password login for it. A database
-- without that role, such as the test container, keeps password login.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${application_role}') THEN
    CREATE ROLE "${application_role}" LOGIN;
  END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'rds_iam') THEN
    GRANT rds_iam TO "${application_role}";
  END IF;
END
$$;

GRANT USAGE ON SCHEMA data_processing, securities_data TO "${application_role}";
GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA data_processing, securities_data TO "${application_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA data_processing, securities_data
  TO "${application_role}";
-- Tables and sequences later migrations create get the same grants.
ALTER DEFAULT PRIVILEGES IN SCHEMA data_processing, securities_data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${application_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA data_processing, securities_data
  GRANT USAGE, SELECT ON SEQUENCES TO "${application_role}";
