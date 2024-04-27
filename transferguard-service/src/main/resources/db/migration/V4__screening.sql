CREATE TABLE screening (
  id              uuid          PRIMARY KEY,
  transaction_id  varchar(64)   NOT NULL UNIQUE,
  request_hash    char(64)      NOT NULL,          -- SHA-256 of the canonical request body
  source_account  varchar(32)   NOT NULL,
  payee_account   varchar(32)   NOT NULL,
  amount          numeric(15,2) NOT NULL CHECK (amount > 0),
  currency        char(3)       NOT NULL,
  channel         varchar(16)   NOT NULL,
  device_id       varchar(128),
  score           smallint      NOT NULL,
  decision        varchar(10)   NOT NULL CHECK (decision IN ('ALLOW','CHALLENGE','HOLD')),
  degraded        boolean       NOT NULL DEFAULT false,
  outcome         varchar(20)   CHECK (outcome IN ('COMPLETED','CHALLENGE_FAILED','CANCELLED')),
  outcome_at      timestamptz,
  requested_at    timestamptz   NOT NULL,
  created_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE screening_reason (
  screening_id  uuid         NOT NULL REFERENCES screening (id),
  position      smallint     NOT NULL,
  code          varchar(40)  NOT NULL,
  points        smallint     NOT NULL CHECK (points >= 0),
  message       varchar(500) NOT NULL,
  PRIMARY KEY (screening_id, position)
);
