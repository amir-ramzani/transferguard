-- One row per account per day of completed transfers. The 30-day average is a sum over at most 30 rows.
CREATE TABLE account_daily_stats (
  account_no  varchar(32)   NOT NULL,
  day         date          NOT NULL,
  amount_sum  numeric(17,2) NOT NULL,
  txn_count   integer       NOT NULL,
  PRIMARY KEY (account_no, day)
);
