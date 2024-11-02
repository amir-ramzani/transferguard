# ADR 003: Fail degraded when Redis is down

**Status:** Accepted · **Date:** 2024-11-02

## Context
If Redis is slow or unavailable, the screening path has three options. Fail open (allow everything)
keeps customers happy and loses money to fraud. Fail closed (challenge everything) stops fraud and
floods customers with OTPs and the contact centre with calls. Neither extreme is obviously right for
every bank or every incident.

## Decision
Default to **DEGRADED**: run the rules PostgreSQL can still answer (R2 known payee from completed
screenings, R3 from `account_daily_stats`, R7 from `watchlist_entry`, and R4, which needs no data),
skip R1, R5 and R6, raise any transfer of RM 1,000 or more to at least CHALLENGE, and mark the
screening `degraded=true` with a `DEGRADED_SCREENING` reason that names the skipped rules.

The fail mode is a setting (`PUT /settings/engine`): `OPEN` allows everything unscreened, `CLOSED`
runs the PostgreSQL rules and challenges every transfer. Idempotency falls back to the unique
constraint on `screening.transaction_id`.

Detection: 50 ms Lettuce timeout; Resilience4j breaker opens at 50% failures over 20 calls and probes
again after 10 s. Readiness does **not** include Redis, so Kubernetes keeps routing traffic.

PostgreSQL is different: if a decision cannot be recorded, the API returns 503 and makes no decision.

## Consequences
- The trade-off is explicit, measurable and changeable during an incident.
- The chaos test runs all three modes and the postmortem compares them (`docs/postmortem.md`).
- After recovery, R1 under-counts until the rebuild job replays the last hour of attempts.
