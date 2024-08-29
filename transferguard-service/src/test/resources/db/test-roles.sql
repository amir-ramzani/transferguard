-- Same role split as infra/postgres: Flyway runs as the migrator (schema owner), the app as transferguard_app.
CREATE ROLE transferguard_migrator LOGIN PASSWORD 'migrator';
CREATE ROLE transferguard_app LOGIN PASSWORD 'app';
ALTER SCHEMA public OWNER TO transferguard_migrator;
