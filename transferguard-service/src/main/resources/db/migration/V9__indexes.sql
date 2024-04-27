CREATE INDEX screening_source_created_idx ON screening (source_account, created_at DESC);
CREATE INDEX fraud_case_assignee_status_idx ON fraud_case (assignee, status);
-- Degraded-mode known-payee lookup and the Redis rebuild job.
CREATE INDEX screening_completed_payee_idx ON screening (source_account, payee_account) WHERE outcome = 'COMPLETED';
CREATE INDEX screening_outcome_at_idx ON screening (outcome_at) WHERE outcome = 'COMPLETED';
