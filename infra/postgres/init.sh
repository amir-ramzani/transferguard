#!/bin/bash
# Runs once, on an empty data volume. Creates the migrator (schema owner, used by Flyway) and the
# app role (used at runtime; it gets table grants from V10 and can never rewrite audit rows).
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_pw="$TG_APP_PASSWORD" -v migrator_pw="$TG_MIGRATOR_PASSWORD" -v db="$POSTGRES_DB" <<'EOSQL'
CREATE ROLE transferguard_migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE transferguard_app LOGIN PASSWORD :'app_pw';
ALTER DATABASE :"db" OWNER TO transferguard_migrator;
ALTER SCHEMA public OWNER TO transferguard_migrator;
EOSQL
