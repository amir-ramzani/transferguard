-- Detection rules are data: weights and parameters are tuned through the API, not redeploys.
CREATE TABLE rule (
  code        varchar(40)  PRIMARY KEY,
  name        varchar(120) NOT NULL,
  enabled     boolean      NOT NULL,
  weight      smallint     NOT NULL CHECK (weight BETWEEN 0 AND 100),
  params      jsonb        NOT NULL,   -- validated against the rule's parameter record before saving
  version     integer      NOT NULL DEFAULT 1,
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  updated_by  varchar(128) NOT NULL DEFAULT 'system'
);
