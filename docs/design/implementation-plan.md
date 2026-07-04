# TransferGuard — Implementation Plan

Nov 19, 2026

## 1. Name: TransferGuard

Rename the project to **TransferGuard: real-time scam and mule-account screening for bank transfers**. Keep *Pengawal* as the Malay codename in the README and in Bank Islam conversations. "Pengawal" means "guard", so the English name keeps the original meaning.

Why this works for a global security company such as F-Secure:

- A reviewer who doesn't speak Malay sees what it does from the name alone.
- It follows the usual security-product pattern of a plain object plus a protective verb.
- It suits F-Secure's consumer focus on scam protection, and it still reads as banking-grade for Bank Islam.

| Name | Reads as | Trade-off |
| --- | --- | --- |
| **TransferGuard** (recommended) | Guards transfers; same meaning as Pengawal | A generic word, so check GitHub and MyIPO for clashes before you publish |
| MuleWatch | Mule-account focus | Undersells the scam-transfer screening, which is most of the project |
| ScamGate | A checkpoint for scams | Sounds like a news headline ("-gate") |
| Avoid: ScamShield, payShield, Sentinel | n/a | Already taken: Singapore's anti-scam app, Thales' payment HSM and Microsoft's SIEM. A security reviewer will notice the clash. |

Rename checklist: repo `transferguard`, Java base package `io.github.<username>.transferguard`, Docker image `ghcr.io/<username>/transferguard`, and Helm release `transferguard`. Put the codename once in the README subtitle: *TransferGuard (codename Pengawal)*.

## 2. Changes to the original spec

The design holds up, but nine gaps need deciding before you write code. The first two are what a reviewer would spot fastest.

| # | Issue in the spec | Decision for the build |
| --- | --- | --- |
| 1 | **Spring Boot 3 is end of life.** Free (OSS) support for 3.5, the last 3.x line, ended on 30 June 2026 ([source](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026)). | Build on **Spring Boot 4.x** (Spring Framework 7, Spring Security 7, Jackson 3) with **Java 21**, and add Java 25 to the CI matrix. Write an ADR on why: a security reviewer will check this first. |
| 2 | The example response says score 75, but R2 + R4 + R6 + R3 = 25 + 10 + 20 + 25 = **80**. | Fix the example to 80 (it is still a HOLD). Add a golden-file test that pins the example payload to its response. |
| 3 | R1 says "more than 5 in 10 minutes" but not whether the current transfer counts. | It counts. The rule fires when the window holds 6 or more transfers, including this one. Parameters: `maxCount=5`, `window=PT10M`. |
| 4 | R3 has no rule for new accounts. With no history the average is 0, so the rule either divides by zero or always fires. | Skip R3 unless `txn_count_30d >= 3`. Record the skip as a trace attribute, not as a reason code. |
| 5 | R2 learns known payees only from screenings, so every payee looks new at first. | The simulator seeds 30 days of history. A payee becomes known only after a transfer to it **completes** (see #6). |
| 6 | Nothing tells the service whether a transfer actually went through. A CHALLENGE might fail its OTP, and a HOLD might be released. | Add `POST /screenings/{id}/outcome` with `COMPLETED`, `CHALLENGE_FAILED` or `CANCELLED`. Only completed transfers update the known-payee set, the 30-day profile and R5's outbound sum. |
| 7 | R5's windows are vague: "then 80% sent out within 60 minutes". | Use one window, `PT60M`. Fire when the last hour holds ≥ 3 inbound credits from distinct senders **and** the outbound total for that hour, including this transfer, is ≥ 80% of the inbound total. All three numbers are parameters. |
| 8 | "Cache rules in Redis" doesn't cover several pods. | Each pod keeps an in-memory Caffeine cache of rules. A change publishes on the Redis channel `tg:rules:changed` and every pod reloads. A 60-second time-to-live is the backstop if a message is lost. |
| 9 | The audit chain says "a hash of the previous row" without a format, and concurrent writers would fork the chain. | `hash = SHA-256(prev_hash ‖ canonical_json(row))`. Writes are serialised with `pg_advisory_xact_lock`. Details are in section 8. |

## 3. Timeline

Plan for nine weeks from Monday 23 November 2026, at about 25–30 hours a week. Milestone 1 goes public on 4 December, so the project is on your CV within two weeks even if later milestones slip.

[embedded diagram, not exported: TransferGuard build timeline · 5 milestones over 9 weeks]

The dashed line marks the M1 release. The gap after M3 is a Christmas buffer. Change the dates in the drawing's rows if your hours differ.

**Week by week**

- [ ] **Week 1 (23 Nov):** generate a Spring Boot 4 project; Flyway V1–V6; Docker Compose; `POST /screenings` with validation; the `Rule` interface; R7 and R2
- [ ] **Week 2 (30 Nov):** `screen_features.lua` and `FeatureLoader`; R1 and R3; `DecisionPolicy`; idempotency with the 20-request concurrency test; the golden test; the README; publish the repo on 4 Dec
- [ ] **Week 3 (7 Dec):** Cognito resource server and scopes; the full CI pipeline; threat model; ADR 004
- [ ] **Weeks 4–5 (14–24 Dec):** event and outcome endpoints; R4, R5 and R6; case API; rules admin with pub/sub; audit chain and verifier
- [ ] **Weeks 6–7 (28 Dec–8 Jan):** metrics, dashboard and tracing; the labelled simulator; k6 results; precision and recall table
- [ ] **Weeks 8–9 (11–22 Jan):** Helm on kind; Toxiproxy chaos in three fail modes; postmortem; ADRs 001–004; optional AWS demo

## 4. Codebase structure and core interfaces

Use a Maven multi-module build with two deployables: `transferguard-service` (the API) and `transferguard-simulator` (the traffic generator). Package by feature, not by layer, so each rule and each domain area can be read and tested on its own. Enforce the package boundaries with an ArchUnit test.

| Package (under `io.github.<username>.transferguard`) | Holds | Key types |
| --- | --- | --- |
| `screening` | REST controller, orchestration, decision mapping | `ScreeningController`, `ScreeningService`, `DecisionPolicy`, `ScreeningResult` |
| `rules` | Rule interface, R1–R7, parameter records, rule admin API | `Rule`, `RuleRegistry`, `RuleConfigService`, `VelocityRule` … `WatchlistRule` |
| `features` | Redis reads and writes, Lua scripts, degraded mode | `FeatureLoader`, `FeatureRecorder`, `Features`, `RedisHealthGate` |
| `events` | Inbound credit and security event ingestion | `EventController`, `InboundCreditService`, `SecurityEventService` |
| `cases` | Case queue, resolution, notes | `CaseController`, `CaseService`, `FraudCase` |
| `watchlist` | Mule-account watchlist | `WatchlistService`, `WatchlistEntry` |
| `profile` | 30-day account statistics | `AccountProfileService`, `DailyStatsRepository` |
| `audit` | Hash-chained audit log and the verifier | `AuditWriter`, `AuditVerifier`, `AuditEntry` |
| `config` | Security, Redis, OpenAPI, clock, time zone | `SecurityConfig`, `RedisConfig`, `ClockConfig` |

**Rule contract.** Each rule is a Spring bean. It gets the pre-loaded features and returns a hit or nothing, with no input/output (I/O) of its own, so a rule unit test needs no mocks.

```java
public interface Rule<P extends RuleParams> {
    RuleCode code();
    Class<P> paramsType();                       // e.g. VelocityParams
    FeatureNeeds needs(P params);                // windows and keys this rule reads
    Optional<RuleHit> evaluate(ScreeningContext ctx, P params);
}

public record RuleHit(RuleCode code, int points, String message) {}
public record VelocityParams(@Min(1) int maxCount, @NotNull Duration window) implements RuleParams {}
public record ScreeningContext(ScreeningRequest request, Features features, ZonedDateTime localTime) {}
```

**Engine flow in code.** `ScreeningService.screen()` gets the enabled rules from `RuleRegistry`, merges their `FeatureNeeds`, calls `FeatureLoader.load()` once (a single Redis pipeline), runs every rule, sums the points, and passes the total to `DecisionPolicy`. Adding R8 means one new class plus one Flyway insert, with no engine change.

**Conventions.** Money is `BigDecimal` with scale 2, never `double`. Time comes from an injected `Clock` and decisions use the `Asia/Kuala_Lumpur` zone, so R4 tests can fix the time. Request handling runs on virtual threads (`spring.threads.virtual.enabled=true`). Errors use RFC 9457 `ProblemDetail` responses.

## 5. Database schema and migrations

PostgreSQL holds the record and Redis holds only data that can be rebuilt. Nine Flyway migrations build the schema, and each one is small enough to review alone. This plan adds three things to the original spec: daily statistics instead of a running average, a settings table for the decision bands, and outcome columns on `screening`.

| Migration | Creates | Notes |
| --- | --- | --- |
| `V1__rule.sql` | `rule` | `params JSONB` is checked against the rule's parameter record before saving |
| `V2__seed_rules.sql` | R1–R7 rows | Default weights and parameters from the spec |
| `V3__engine_setting.sql` | `engine_setting(key, value JSONB)` | Decision bands `{allowMax:39, challengeMax:69}` and the fail mode |
| `V4__screening.sql` | `screening`, `screening_reason` | Unique `transaction_id`; `request_hash`, `degraded`, `outcome`, `outcome_at` |
| `V5__account_stats.sql` | `account_daily_stats(account_no, day, amount_sum, txn_count)` | Primary key `(account_no, day)`; the 30-day average is a sum over 30 rows |
| `V6__watchlist.sql` | `watchlist_entry` | Read on every screening, so it is also mirrored in a Redis set |
| `V7__cases.sql` | `fraud_case`, `case_note` | A partial index on `status = 'OPEN'` keeps the queue query fast |
| `V8__audit_log.sql` | `audit_log` | Insert-only (below) |
| `V9__indexes.sql` | Secondary indexes | `screening(source_account, created_at DESC)` and `fraud_case(assignee, status)` |

**Why daily stats replace `account_profile`.** A true 30-day average needs to forget old days. A running average updated per screening can't do that. One upsert per completed transfer into `account_daily_stats`, plus one indexed query over at most 30 rows, gives an exact figure in well under a millisecond. Cache the result in Redis for 5 minutes.

**Core DDL (excerpt).**

```sql
CREATE TABLE screening (
  id              uuid PRIMARY KEY,
  transaction_id  varchar(64)  NOT NULL UNIQUE,
  request_hash    char(64)     NOT NULL,          -- SHA-256 of the canonical request body
  source_account  varchar(32)  NOT NULL,
  payee_account   varchar(32)  NOT NULL,
  amount          numeric(15,2) NOT NULL CHECK (amount > 0),
  currency        char(3)      NOT NULL,
  channel         varchar(16)  NOT NULL,
  score           smallint     NOT NULL,
  decision        varchar(10)  NOT NULL CHECK (decision IN ('ALLOW','CHALLENGE','HOLD')),
  degraded        boolean      NOT NULL DEFAULT false,
  outcome         varchar(20),
  outcome_at      timestamptz,
  requested_at    timestamptz  NOT NULL,
  created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor      varchar(128) NOT NULL,     -- JWT subject, or 'system'
  action     varchar(64)  NOT NULL,     -- RULE_UPDATED, CASE_RESOLVED, WATCHLIST_ADDED ...
  entity     varchar(128) NOT NULL,
  before     jsonb,
  after      jsonb,
  prev_hash  char(64)     NOT NULL,
  hash       char(64)     NOT NULL UNIQUE,
  created_at timestamptz  NOT NULL
);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM transferguard_app;
```

The application connects as `transferguard_app`, which may not update or delete audit rows. Flyway runs as a separate `transferguard_migrator` role. This split between the migration role and the runtime role is a point security reviewers look for.

Scale note for the interview: at real bank volume, `screening` would be partitioned by month, with old partitions archived to S3. Mention this in an ADR, but don't build it.

## 6. Redis feature store

Each screening reads every feature in **one network round trip**: one Lua script for the source account's keys, pipelined with one watchlist lookup. Per-account keys share a `{hash tag}`, so they land in the same cluster slot and the Lua script still works on ElastiCache in cluster mode.

| Key | Type | Contents | Written by | Read by | Expiry |
| --- | --- | --- | --- | --- | --- |
| `tg:{acct}:out` | Sorted set | member = `txnId`, score = epoch ms (every attempt) | Screening (Lua) | R1 | Trimmed to the largest rule window; key expires after 24 h |
| `tg:{acct}:outdone` | Sorted set | member = `txnId\|amount`, score = epoch ms (completed transfers only) | Outcome endpoint | R5 | Trimmed to 60 min |
| `tg:{acct}:in` | Sorted set | member = `creditId\|sender\|amount` | Inbound credit event | R5 | Trimmed to 60 min |
| `tg:{acct}:payees` | Set | Known payee accounts | Outcome = COMPLETED | R2 | 400 days, rebuildable from `screening` |
| `tg:{acct}:sec` | Hash | `deviceId`, `deviceChangedAt`, `pwdResetAt` | Security event | R6 | 30 days |
| `tg:{acct}:avg` | String | `avg\|count` from `account_daily_stats` | Cache fill on miss | R3 | 5 min |
| `tg:watchlist` | Set | Reported mule accounts (mirror of PostgreSQL) | Watchlist API, startup sync | R7 | None |
| `tg:idem:{txnId}` | String | `PENDING` or the screening ID | Screening | API | 24 h |
| `tg:rules:changed` | Pub/sub channel | Rule code that changed | Rules admin API | All pods | n/a |

**The screening script (`screen_features.lua`)** runs atomically and returns everything the rules need:

1. `ZREMRANGEBYSCORE out -inf (now - maxWindow)` trims old attempts.
2. `ZADD out NX now txnId` records this attempt. Because the member is the transaction ID, a retried request can never be counted twice. This makes the spec's idempotency promise for velocity windows hold by construction.
3. `ZCOUNT out (now - r1Window) +inf` gives R1's count, including this transfer.
4. `ZRANGEBYSCORE in` and `ZRANGEBYSCORE outdone` over R5's window return the raw credits and debits. Java counts distinct senders and sums the amounts.
5. `SISMEMBER payees payee`, `HGETALL sec` and `GET avg` cover R2, R6 and R3.
6. `PEXPIRE` refreshes the key expiries.

Window lengths come from the active rule parameters (`FeatureNeeds`), so an analyst who changes R1 from 10 to 15 minutes needs no code change.

**Latency budget for p95 under 100 ms.** Redis round trip 5 ms or less; rule evaluation under 1 ms; PostgreSQL write transaction 15 ms or less; JSON, security filter and JWT check 10 ms or less. That leaves roughly 60 ms of headroom on a laptop or kind cluster. Set the Lettuce command timeout to **50 ms** so a slow Redis can't break the target; section 8 covers what happens then.

## 7. Screening pipeline and rule details

A screening is nine steps with one database transaction. Only step 7 writes to PostgreSQL. The rules themselves never do I/O.

1. **Validate.** Bean Validation checks the body: amount > 0 with 2 decimal places, currency `MYR`, and account numbers matching a pattern. Reject `requestedAt` if it is more than 5 minutes away from the server clock, because R4 trusts it.
2. **Claim the idempotency key.** Run `SET tg:idem:{txnId} PENDING NX EX 86400`.
   - If the key holds a screening ID, load that screening. If its `request_hash` matches, return it with `200` and the header `Idempotent-Replay: true`. If the hash differs, return `422` (same ID, different body).
   - If the key holds `PENDING`, return `409` with `Retry-After: 1`.
3. **Load the rules** from the in-memory Caffeine cache (section 2, #8).
4. **Load the features** in one Redis round trip (section 6). This step also records the attempt.
5. **Evaluate** every enabled rule. Each hit adds its points and its message.
6. **Decide.** `DecisionPolicy` maps the score using the bands in `engine_setting`.
7. **Persist** in one transaction: `screening`, its `screening_reason` rows, and a `fraud_case` if the decision is HOLD.
8. **After commit,** overwrite the idempotency key with the screening ID, and record metrics and trace attributes.
9. **Respond** with `201 Created` and a `Location: /api/v1/screenings/{id}` header.

If the transaction in step 7 fails, delete the idempotency key so the caller can retry. The unique constraint on `transaction_id` is the final guard if Redis and PostgreSQL disagree.

| Rule | Parameters (JSONB) | Fires when | Edge cases to test |
| --- | --- | --- | --- |
| `R1_VELOCITY` | `{maxCount:5, window:"PT10M"}` | Attempts in the window, including this one, are greater than `maxCount` | The 6th attempt fires and the 5th doesn't; a replayed request doesn't count; an attempt at exactly 10:00 old has left the window |
| `R2_NEW_PAYEE_HIGH_AMOUNT` | `{minAmount:3000.00}` | Payee not in the known set **and** amount ≥ `minAmount` | RM 2,999.99 doesn't fire; a payee that was only challenged and never completed is still new |
| `R3_AMOUNT_ANOMALY` | `{multiplier:5, minHistory:3}` | `txnCount30d ≥ minHistory` **and** amount ≥ multiplier × average | No history means skip; exactly 5× fires; the message shows the rounded multiple ("8x") |
| `R4_ODD_HOURS` | `{start:"00:00", end:"06:00", minAmount:1000.00, zone:"Asia/Kuala_Lumpur"}` | Local time in `[start, end)` **and** amount ≥ `minAmount` | 05:59:59 fires and 06:00 doesn't; a UTC timestamp converts correctly |
| `R5_PASS_THROUGH` | `{window:"PT60M", minSenders:3, outRatio:0.80}` | Distinct inbound senders ≥ 3 **and** (completed outbound + this amount) ≥ 80% of inbound total | 3 credits from one sender doesn't fire; no inbound means skip without dividing by zero |
| `R6_NEW_DEVICE` | `{window:"PT30M"}` | Request `deviceId` differs from the stored one, **or** a device change or password reset happened in the window | First device ever seen (no hash) doesn't fire; reset 31 minutes ago doesn't fire |
| `R7_WATCHLIST` | `{}` | Payee is in `tg:watchlist` | Watchlist add is visible in Redis and PostgreSQL; removal is audited |

**Reason messages** are built in the rule from a template with the numbers filled in ("Amount is 8x the 30-day average"). Keep them in English, with the reason code as the stable key, so a bank's front end can translate the code into Bahasa Melayu for customers.

## 8. Idempotency, failure handling and the audit chain

**Recommendation for ADR 003: fail degraded, not simply open or closed.** When Redis is unavailable, run the rules PostgreSQL can still answer, raise the floor for larger amounts, and flag the screening. This is a stronger interview answer than either extreme, because it shows you weighed both customer friction and fraud loss.

| Failure | Detection | Behaviour | Recovery |
| --- | --- | --- | --- |
| Redis slow or down | 50 ms command timeout; a Resilience4j circuit breaker opens at 50% failures over 20 calls and probes again after 10 s | **Degraded mode:** run R2 (known payee from `screening`), R3 (from `account_daily_stats`) and R7 (from `watchlist_entry`); skip R1, R5 and R6. If the amount is ≥ RM 1,000, the decision is at least CHALLENGE. Set `degraded=true` and add the reason `DEGRADED_SCREENING`. | When the breaker closes, a job rebuilds `out`, `outdone` and `payees` for the last 60 minutes from `screening`. Without this, R1 under-counts for 10 minutes after the outage. |
| Idempotency without Redis | Same breaker | `INSERT … ON CONFLICT (transaction_id) DO NOTHING RETURNING id`; on conflict, return the stored screening | None needed |
| PostgreSQL down | Hikari connection timeout of 250 ms | Return `503` with `ProblemDetail`. Never return a decision that wasn't recorded, because an unrecorded decision breaks the audit trail. The bank's transfer service applies its own fallback. | Readiness probe fails, so Kubernetes stops sending traffic to the pod |
| Rule cache message lost | 60 s cache expiry | A stale rule for at most 60 s | Automatic |

The fail mode is a setting in `engine_setting` (`OPEN`, `DEGRADED` or `CLOSED`), so the chaos test in section 10 can run all three and compare them in the postmortem.

**Audit chain.** Every administrative change writes one `audit_log` row in the **same transaction** as the change itself: rule and setting updates, watchlist changes, case assignment, case resolution and case notes. Screenings are not audited this way. They are already insert-only records.

1. `SELECT pg_advisory_xact_lock(<fixed key>)` serialises writers, so the chain can't fork under concurrent updates.
2. Read the latest `hash`. For the first row, use 64 zeros.
3. Canonicalise `{actor, action, entity, before, after, created_at}` with RFC 8785 (JSON Canonicalization Scheme), so key order and number formatting can't change the hash.
4. `hash = hex(SHA-256(prev_hash ‖ canonical_json))`, then insert.

`GET /api/v1/audit/verify` (scope `audit:read`) recomputes the chain and returns the first broken row ID. A nightly job does the same and publishes the gauge `transferguard_audit_chain_valid` with an alert on 0.

A hash chain shows that rows were edited, but not that the newest rows were deleted. Close that gap by writing the latest hash to an S3 bucket with Object Lock once an hour. Saying this unprompted is a strong signal to a security interviewer.

## 9. Security hardening

This section turns a fraud demo into a project a cybersecurity company respects. Add a **threat model** (`docs/threat-model.md`, using STRIDE: spoofing, tampering, repudiation, information disclosure, denial of service, elevation of privilege) in Milestone 2, and link every control below to the threat it handles.

| Threat | Control | Where |
| --- | --- | --- |
| Forged or stolen tokens | OAuth2 resource server checks the Cognito JWKS signature, `iss`, `token_use=access`, `client_id` and expiry. Access tokens last at most 15 minutes. | `SecurityConfig` |
| Over-privileged callers | Cognito custom scopes (`transferguard/screening.write` and so on) map to Spring authorities. Machine callers such as the simulator use client credentials. Analysts use the authorization code flow with PKCE. | Cognito resource server, `@PreAuthorize` |
| Insider tampering with rules | Every rule change goes to the audit chain. Optional four-eyes control: a change stays `PENDING` until a second analyst approves it. | `rules`, `audit` |
| Fraudsters probing thresholds | Reason codes go only to the bank's backend, never to end customers. Rate-limit each client with Bucket4j. Alert on bursts of screenings that land just under a threshold. | API gateway, metrics |
| Personal data leaking into logs and traces | A Logback converter masks account numbers (`1402-****-3456`). No account numbers in trace attributes or metric labels. Note the Malaysian PDPA in the README. | `logback-spring.xml`, OTel config |
| Exposed internals | Actuator runs on a separate management port and exposes only `health` and `prometheus`. Swagger UI is turned off in the `prod` profile. | `application-prod.yml` |
| Secrets in the repo | gitleaks runs in CI and as a pre-commit hook. In Kubernetes, secrets come from AWS Secrets Manager through External Secrets Operator. Pods get AWS access through IAM roles for service accounts (IRSA). | CI, Helm chart |
| Vulnerable dependencies | Renovate keeps dependencies current. Trivy scans the code and the image, and the build fails on HIGH or CRITICAL findings. CodeQL and SonarCloud run static analysis. | CI |
| Tampered images | A CycloneDX software bill of materials (SBOM) is attached to every image. Images are signed with cosign (keyless, using GitHub OIDC). A distroless JRE base image runs as a non-root user with a read-only root file system. | CI, `Dockerfile` |

**Optional, and a strong link to your other project:** a multi-bank mode. The `tenant_id` claim from your multitenant Cognito OAuth project goes into every table, and PostgreSQL row-level security keeps each bank's data apart. It shows one design used across two projects. Leave it until after Milestone 5.

## 10. Observability, testing, CI, load and chaos

**Metrics** (Micrometer to Prometheus, one Grafana dashboard committed as JSON):

| Metric | Type | Labels | Panel or alert |
| --- | --- | --- | --- |
| `transferguard_screenings_total` | Counter | `decision`, `degraded` | Decision mix; alert if the HOLD rate is over 3× its 1-hour baseline |
| `transferguard_rule_hits_total` | Counter | `rule` | Hit rate per rule (a sudden jump usually means a bad tuning change) |
| `http_server_requests_seconds` | Histogram | `uri`, `status` | p50, p95 and p99 latency; alert if p95 is over 100 ms for 5 minutes |
| `transferguard_feature_load_seconds` | Timer | `mode` (normal, degraded) | Redis share of the latency |
| `transferguard_cases_open` | Gauge | none | Queue depth |
| `transferguard_case_resolutions_total` | Counter | `outcome` | False-positive rate = FALSE\_POSITIVE ÷ all resolved |
| `transferguard_audit_chain_valid` | Gauge | none | Alert on 0 |
| `resilience4j_circuitbreaker_state` | Gauge | `name` | Redis breaker open |

**Tracing:** the OpenTelemetry Java agent plus one manual span per stage (`screening.features`, `screening.rules`, `screening.persist`) with attributes for score, decision and rules hit. Logs are JSON with `traceId`, so a log line links to its trace in Grafana Tempo.

**Tests:**

| Layer | What | Tools | Gate |
| --- | --- | --- | --- |
| Unit | Each rule's edge cases (section 7), `DecisionPolicy`, the audit hasher against fixed test vectors | JUnit 5, AssertJ | Line coverage of 80% or more on `rules`, `screening` and `audit` |
| Mutation | The rules package | PIT | A mutation score of 70% or more, which proves the boundary tests really check the boundaries |
| Lua | `screen_features.lua` against a real Redis | Testcontainers Redis | Required |
| Web slice | Controllers, validation, scope checks, `ProblemDetail` shape | `@WebMvcTest` with mocked JWTs | Required |
| Integration | Full flow with PostgreSQL 16 and Redis 7; 20 parallel requests with one `transactionId` produce 1 row and 1 velocity count | Testcontainers | Required |
| Contract | The README example request gives the README example response | Golden JSON file | Required |
| Architecture | Rules never import `features`; controllers never touch repositories | ArchUnit | Required |

**CI (GitHub Actions, `ci.yml`):** on every PR, run checkout; set up JDK 21 and 25 (matrix); `mvn verify` (unit and integration tests); SonarCloud quality gate; CodeQL; gitleaks; Trivy file-system scan. On `main` only, also build the image with Jib, run a Trivy image scan, generate the CycloneDX SBOM, push to GHCR and sign with cosign. A nightly workflow runs the k6 smoke test against Docker Compose.

**Simulator scenarios.** Each generated transfer carries a ground-truth label, so the README can report precision and recall for each rule, not just latency.

- *Normal:* salary in, then bills, groceries and transfers to family on daily and weekly cycles.
- *Account takeover:* a password reset, then a new device, then a large transfer to a new payee at 02:00.
- *Mule ring:* 4 to 6 victims credit one account, which sends 85–95% onward within 30 minutes.
- *Velocity burst:* 8 small transfers in 4 minutes.
- *Known mule:* a payee on the watchlist.

**Load test (k6, `load-test/screening.js`):** a constant arrival rate of 200 requests per second for 5 minutes, with 90% normal and 10% scam traffic. Thresholds: `http_req_duration p(95)<100`, `http_req_failed rate<0.001`. Publish a results table, the hardware it ran on and a Grafana screenshot in the README.

**Chaos experiment.** Put Toxiproxy between the app and Redis. During the load test: at minute 2, add 200 ms latency for 60 seconds; at minute 3, cut the connection for 60 seconds. Run it once per fail mode (`OPEN`, `DEGRADED`, `CLOSED`). Record p95, error rate, decision mix, number of degraded screenings, time to recover, and how long R1 under-counts after recovery. Write the postmortem from those numbers: timeline, impact, root cause, what went well, and actions.

## 11. Deployment and definition of done

**Local:** `docker compose up` starts the app, PostgreSQL 16, Redis 7, Toxiproxy, Prometheus, Grafana, Tempo and the simulator. `make demo` seeds history and runs the five scenarios. A reviewer should go from `git clone` to a HOLD decision in Swagger in under 5 minutes.

**Kubernetes (Helm chart, tested on kind):**

- A Deployment with 3 replicas and a PodDisruptionBudget (`minAvailable: 2`). A HorizontalPodAutoscaler scales on CPU at 70%.
- Liveness uses `/actuator/health/liveness`. Readiness includes PostgreSQL but **not** Redis, because degraded mode handles a Redis outage. Pulling pods out of traffic would turn a partial outage into a full one.
- `securityContext`: `runAsNonRoot`, `readOnlyRootFilesystem`, all capabilities dropped. A NetworkPolicy allows only ingress to the app, and app traffic to PostgreSQL, Redis and the OTel collector.
- PostgreSQL through the CloudNativePG operator, Redis as a single StatefulSet, and Flyway as a pre-install/pre-upgrade Job using the migrator role.

**AWS (stretch):** ECS Fargate is simpler and cheaper to demo than EKS. Use RDS PostgreSQL, ElastiCache Redis, Secrets Manager and an Application Load Balancer, defined in CloudFormation or CDK (you already know CloudFormation). Run it for one day, record a demo video, then tear it down. Set an AWS Budgets alert first.

| Milestone | Done when |
| --- | --- |
| M1 Core screening | `docker compose up` works first time on a clean machine; R1, R2, R3, R7 and idempotency pass unit and Testcontainers tests; the README example matches the golden test; the resume line is live |
| M2 Security and CI | Calls without a valid token or scope get 401 or 403; the CI badge is green with the SonarCloud gate, CodeQL, Trivy and gitleaks; the threat model is committed |
| M3 Mule detection and cases | The account-takeover and mule-ring scenarios end in HOLD with the expected reasons; an analyst resolves a case through Swagger; `audit/verify` passes, and fails after a manual row edit |
| M4 Observability and performance | The Grafana dashboard JSON is committed; the k6 run meets p95 < 100 ms at 200 requests per second; the README shows latency and per-rule precision and recall |
| M5 Kubernetes, cloud, postmortem | `helm install` on kind passes a smoke test; the chaos experiment has run in all three fail modes; the postmortem and ADRs 001–004 are published (004 is the move to Spring Boot 4) |

## 12. Risks and interview story

| Risk | Effect | Mitigation |
| --- | --- | --- |
| Scope creep into ML or a front end | M1 slips and nothing ships | M1 alone is portfolio-ready. Publish it before starting M2. |
| Spring Boot 4 changes (Jackson 3, Security 7, split starters) | Fewer Stack Overflow answers; slower first week | Generate the project with start.spring.io; rely on the official migration notes; record the issues you hit in ADR 004 as interview material |
| Testcontainers is slow or flaky in CI | PRs wait or fail at random | Reuse one container set per test JVM (singleton containers); cache Maven dependencies |
| Simulated data looks fake | The precision and recall numbers mean little | Say plainly that the data is synthetic; publish the generator's parameters; present the numbers as a regression baseline, not as real-world accuracy |
| Cloud costs | A surprise bill | Prefer kind; use AWS only with a Budgets alert and same-day teardown |

**One story, two audiences.** Lead with whichever side the interviewer cares about.

- *For a bank such as Bank Islam:* explainable decisions for regulators, rules tuned without redeploys, an audit trail that shows tampering, and the same Java, Spring, PostgreSQL, Redis, Kubernetes and AWS stack. It builds on the TAC/OTP and transfer flows you built at Cyber Village.
- *For a security company such as F-Secure:* scams and mules as a threat problem, a STRIDE threat model, supply-chain controls (SBOM, signing, scanning), and a fail-degraded design proven by a chaos test and a blameless postmortem. This draws directly on your 15 years at F-Secure and your EIRT incident-response work.

**Updated resume line (after M1):**

> **TransferGuard: real-time scam and mule-account screening** (personal project) · Java 21, Spring Boot 4, PostgreSQL, Redis, Docker Built a transfer-screening service that scores outgoing payments against tunable fraud rules and returns allow, challenge or hold decisions in under 100 ms at p95, with an idempotent API, Testcontainers integration tests and CI security gates. github.com/\<username>/transferguard

After M4 and M5, add a second line: *"Chaos-tested Redis failure across three fail modes; published a threat model and a blameless postmortem."*
