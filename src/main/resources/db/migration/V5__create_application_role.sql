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
    -- rds_iam would end the password login the migrations use, so the application role must not
    -- be the user running them: the migration function's DATABASE_USERNAME names the application
    -- role, and the migration logs in as the master user from the secret.
    IF '${application_role}' = current_user THEN
      RAISE EXCEPTION 'The application role % must not be the user running the migrations',
        current_user;
    END IF;
    GRANT rds_iam TO "${application_role}";
  END IF;
END
$$;

GRANT USAGE ON SCHEMA data_processing, securities_data TO "${application_role}";
GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA data_processing, securities_data TO "${application_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA data_processing, securities_data
  TO "${application_role}";
-- Tables and sequences later migrations create get the same grants. Default privileges apply to
-- objects the user running this creates, which is the master user that runs every migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA data_processing, securities_data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${application_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA data_processing, securities_data
  GRANT USAGE, SELECT ON SEQUENCES TO "${application_role}";

-- The grant on all tables included Flyway's history table; only migrations may change it. Where
-- the application role runs the migrations itself, as in tests, it owns the table and keeps it.
DO $$
BEGIN
  IF '${application_role}' <> current_user THEN
    REVOKE ALL ON TABLE "${flyway:defaultSchema}"."${flyway:table}" FROM "${application_role}";
  END IF;
END
$$;
