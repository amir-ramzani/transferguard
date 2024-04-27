CREATE SEQUENCE fraud_case_seq START WITH 1;

CREATE TABLE fraud_case (
  id               bigint       PRIMARY KEY DEFAULT nextval('fraud_case_seq'),
  case_no          varchar(20)  NOT NULL UNIQUE,     -- CASE-000041
  screening_id     uuid         NOT NULL UNIQUE REFERENCES screening (id),
  status           varchar(20)  NOT NULL CHECK (status IN ('OPEN','CONFIRMED_FRAUD','FALSE_POSITIVE')),
  assignee         varchar(128),
  score            smallint     NOT NULL,
  amount           numeric(15,2) NOT NULL,
  created_at       timestamptz  NOT NULL,
  resolved_at      timestamptz,
  resolved_by      varchar(128),
  resolution_note  varchar(2000)
);

-- The analyst queue reads open cases oldest first.
CREATE INDEX fraud_case_open_idx ON fraud_case (created_at) WHERE status = 'OPEN';

CREATE TABLE case_note (
  id          bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id     bigint        NOT NULL REFERENCES fraud_case (id),
  author      varchar(128)  NOT NULL,
  note        varchar(2000) NOT NULL,
  created_at  timestamptz   NOT NULL
);
CREATE INDEX case_note_case_idx ON case_note (case_id, id);
