-- Hash-chained, insert-only audit trail:
--   hash = hex(SHA-256(prev_hash || JCS({actor, action, entity, before, after, created_at})))
CREATE TABLE audit_log (
  id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor      varchar(128) NOT NULL,     -- JWT subject, or 'system'
  action     varchar(64)  NOT NULL,     -- RULE_UPDATED, CASE_RESOLVED, WATCHLIST_ADDED ...
  entity     varchar(128) NOT NULL,     -- rule:R1_VELOCITY, case:CASE-000041, watchlist:1402-...
  before     jsonb,
  after      jsonb,
  prev_hash  char(64)     NOT NULL,
  hash       char(64)     NOT NULL UNIQUE,
  created_at timestamptz  NOT NULL
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity, id DESC);
