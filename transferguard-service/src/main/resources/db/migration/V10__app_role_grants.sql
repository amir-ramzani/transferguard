-- Runtime role split: Flyway runs as transferguard_migrator (owner); the application connects as
-- transferguard_app, which may read and write business tables but may never rewrite the audit trail.
-- The roles are created outside Flyway (infra/postgres/init.sql). Skipped when the role is absent.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'transferguard_app') THEN
    GRANT USAGE ON SCHEMA public TO transferguard_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO transferguard_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO transferguard_app;
    REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM transferguard_app;
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM transferguard_app;
  END IF;
END
$$;
