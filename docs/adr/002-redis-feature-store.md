# ADR 002: Redis as the feature store

**Status:** Accepted · **Date:** 2024-11-02

## Context
R1 (velocity), R5 (pass-through) and R6 (new device) need counts and sums over sliding windows of
minutes. Querying PostgreSQL history for every screening would add several indexed range scans to a
path that must answer in under 100 ms at p95, and would load the system of record with reads.

## Decision
Keep short-window features in Redis sorted sets, sets and hashes, one key family per account sharing a
`{account}` hash tag. One Lua script (`screen_features.lua`) trims, records the attempt and reads every
feature atomically; it is pipelined with the watchlist lookup, so a screening makes one Redis round
trip. The transaction ID is the sorted-set member, so a retried request can never be counted twice.

## Consequences
- Lettuce runs with a 50 ms command timeout behind a circuit breaker (see ADR 003).
- Redis holds no business fact that PostgreSQL lacks, except the raw inbound-credit and security
  event windows, which the bank also keeps. A rebuild job restores attempts, completed debits, known
  payees and the watchlist from PostgreSQL at startup and whenever Redis recovers.
- The hash tag keeps the script valid on ElastiCache in cluster mode.
