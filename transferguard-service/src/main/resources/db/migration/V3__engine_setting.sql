-- Key/value settings for the decision engine. The 'engine' row holds the decision bands and fail mode.
CREATE TABLE engine_setting (
  key         varchar(64)  PRIMARY KEY,
  value       jsonb        NOT NULL,
  version     integer      NOT NULL DEFAULT 1,
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  updated_by  varchar(128) NOT NULL DEFAULT 'system'
);

INSERT INTO engine_setting (key, value) VALUES
  ('engine', '{"allowMax": 39, "challengeMax": 69, "failMode": "DEGRADED", "degradedChallengeMinAmount": "1000.00"}');
