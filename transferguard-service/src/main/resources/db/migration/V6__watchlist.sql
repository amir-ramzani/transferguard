-- Reported mule accounts. Mirrored to the Redis set tg:watchlist for screening.
CREATE TABLE watchlist_entry (
  account_no  varchar(32)  PRIMARY KEY,
  source      varchar(20)  NOT NULL CHECK (source IN ('CASE','POLICE_REPORT','NFCC','BNM','OTHER_BANK','MANUAL')),
  reason      varchar(500) NOT NULL,
  case_id     varchar(20),
  added_at    timestamptz  NOT NULL,
  added_by    varchar(128) NOT NULL
);
