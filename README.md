# TransferGuard

*TransferGuard (codename Pengawal): real-time scam and mule-account screening for bank transfers.*

TransferGuard screens an outgoing bank transfer before the money leaves the account and answers
**ALLOW**, **CHALLENGE** or **HOLD** with human-readable reasons, targeting under 100 ms at p95. It
also spots mule accounts that receive money from many senders and pass it on quickly.

Java 21 · Spring Boot 3 · PostgreSQL 16 · Redis 7 · Docker Compose · Helm

```http
POST /api/v1/screenings
{ "transactionId": "TXN-20261119-000123", "sourceAccount": "1402-0012-3456",
  "payeeAccount": "1402-0098-7654", "amount": "4800.00", "currency": "MYR",
  "channel": "MOBILE", "deviceId": "dev-7f3a", "requestedAt": "2026-11-19T01:42:10+08:00" }

201 Created
{ "decision": "HOLD", "score": 80, "degraded": false, "caseId": "CASE-000041",
  "reasons": [
    { "code": "R2_NEW_PAYEE_HIGH_AMOUNT", "points": 25, "message": "First transfer to this payee is RM 4,800" },
    { "code": "R3_AMOUNT_ANOMALY",        "points": 25, "message": "Amount is 8x the 30-day average" },
    { "code": "R6_NEW_DEVICE",            "points": 20, "message": "New device seen 12 minutes before this transfer" },
    { "code": "R4_ODD_HOURS",             "points": 10, "message": "Transfer requested at 01:42" } ], ... }
```

This exact pair is pinned twice: by `GoldenExampleTest` (engine level, reads the example straight from
`docs/openapi.yaml`) and by `ScreeningFlowIT#goldenExample` (over HTTP, PostgreSQL and Redis).

## Run it locally

### 1. Install the prerequisites (once)

You need Docker with the Compose plugin (6 GB of memory or more), JDK 21, make, curl and jq. On
Ubuntu or Debian the bootstrap script installs them (it asks for your sudo password):

```bash
./scripts/bootstrap.sh            # install what is missing, then check
./scripts/bootstrap.sh --check    # only check (also: make doctor)
```

The script adds you to the `docker` group. Log out and back in (or run `newgrp docker` in the
terminal you are using) so Docker works without sudo. On macOS it prints the Homebrew commands.

### 2. Start

```bash
cp .env.example .env     # once; change the passwords if the machine is shared
make up                  # builds both images with Jib, starts API + PostgreSQL + Redis + Toxiproxy
                         # + mock OAuth2 + Prometheus + Grafana + Tempo. Flyway migrates on start.
curl -s localhost:8081/actuator/health/readiness   # wait for {"status":"UP"}
make demo                # seeds 30 days of history, then runs the five scenarios
```

On a smaller machine use `make up-core` instead of `make up`: the API and its dependencies only,
about 2 GB, without Prometheus, Grafana and Tempo.

### 3. Check it works

```bash
make smoke               # 71 end-to-end checks over all 19 operations, with real mock-OAuth2 tokens
make postman             # the Postman collection in postman/, run with newman in Docker
```

| Service | URL |
|---|---|
| Swagger UI (click **Authorize**, client ID `local-admin`, any secret) | http://localhost:8080/swagger-ui.html |
| Actuator (management port) | http://localhost:8081/actuator/health |
| Mock OAuth2 issuer | http://localhost:8090/transferguard |
| Grafana (dashboard "TransferGuard") | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| PostgreSQL / Redis (through Toxiproxy) | localhost:5432 / localhost:6379 (Toxiproxy API 8474) |

```bash
TOKEN=$(make -s token)   # an admin access token for curl
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/cases?status=OPEN' | jq
make logs                # follow the API logs
```

Inside the Compose network the services use their names instead of `localhost`
(`transferguard:8080`, `mock-oauth2:8080`); that is what the simulator and k6 containers use.

### 4. Stop

```bash
make down                # stop and remove the containers; the PostgreSQL data is kept
make clean               # also delete the data volume: next start is an empty database (run make demo again)
```

Everything runs in Docker, so this stops the API too. Redis is not persisted; the API rebuilds its
features from PostgreSQL on start. If you change the passwords in `.env` after the first start, run
`make clean` first: the database roles are only created on an empty volume.

### More

`make sim` (5 minutes of labelled traffic and a precision/recall table), `make load` (k6),
`make chaos` (Toxiproxy experiment in all three fail modes). `make help` lists every target.

The demo scenarios run "now", because the API rejects `requestedAt` more than 5 minutes from server
time. R4 (odd hours) therefore only fires if you run them between 00:00 and 06:00 Kuala Lumpur time.
The takeover scenario still ends in HOLD without it (25 + 25 + 20 = 70).

## How it works

One stateless Spring Boot service (a modular monolith, ADR 005) between the bank's systems and two
data stores. PostgreSQL is the system of record; Redis holds short-window features that can be rebuilt.

A screening is: validate → claim the idempotency key (`SET NX`) → load rules from the in-memory cache
→ load all features in **one Redis round trip** (`screen_features.lua`, pipelined with the watchlist
lookup; the script also records the attempt) → evaluate rules in memory → map the score to a decision
→ persist screening, reasons and (for HOLD) a case in **one PostgreSQL transaction** → mark the key done
→ `201 Created`. If PostgreSQL is down the caller gets a 503, never an unrecorded decision.

| Rule | Default | Fires when |
|---|---:|---|
| `R1_VELOCITY` | 40 | More than 5 attempts in 10 minutes, counting this one |
| `R2_NEW_PAYEE_HIGH_AMOUNT` | 25 | RM 3,000 or more to a payee never paid successfully before |
| `R3_AMOUNT_ANOMALY` | 25 | 5× or more the 30-day average (skipped below 3 completed transfers) |
| `R4_ODD_HOURS` | 10 | RM 1,000 or more between 00:00 and 06:00 local time |
| `R5_PASS_THROUGH` | 50 | ≥ 3 distinct senders in the last hour, and outbound (completed + this) ≥ 80% of inbound |
| `R6_NEW_DEVICE` | 20 | Device change or password reset in the last 30 minutes, or an unregistered device |
| `R7_WATCHLIST` | 70 | Payee is a reported mule account |

Bands: score ≤ 39 ALLOW, ≤ 69 CHALLENGE, otherwise HOLD. Weights, parameters and bands are data
(`PUT /rules/{code}`, `PUT /settings/engine`, both with `If-Match`); every pod picks up a change via
Redis pub/sub, with a 60-second cache expiry as the backstop. Reasons are listed highest points first.

**Only completed transfers teach the system.** The bank reports `POST /screenings/{id}/outcome`; a
`COMPLETED` outcome makes the payee known (R2), adds to the 30-day profile (R3) and to R5's outbound
total. A payee that was only challenged stays new.

**When Redis fails** (ADR 003) a 50 ms timeout and a circuit breaker switch screening to the fail mode
in `engine_setting`: `DEGRADED` (default) runs R2, R3, R4 and R7 from PostgreSQL, skips R1, R5 and R6,
raises RM 1,000+ to at least CHALLENGE and flags the screening; `OPEN` allows; `CLOSED` challenges
everything. Idempotency falls back to the unique constraint. When Redis returns, a rebuild job replays
attempts, completed debits, known payees and the watchlist from PostgreSQL.

**Audit.** Rule, settings, watchlist and case changes write a hash-chained `audit_log` row in the same
transaction: `hash = SHA-256(prev_hash ‖ JCS(row))` (RFC 8785), serialised by an advisory lock. The app
database role cannot update or delete audit rows. `GET /audit/verify` returns the first broken row.

### Code map (`io.github.nulled.transferguard`)

| Package | Holds |
|---|---|
| `screening` | Controller, pipeline (`ScreeningService`), `DecisionPolicy`, idempotency, outcomes; `screening.api` DTOs |
| `rules` | `Rule` contract, `RuleEngine`, config cache and admin API; `rules.impl` R1–R7 and their parameter records |
| `features` | Redis gateway and circuit breaker, Lua loader, PostgreSQL fallback loader, recorder, rebuild job, pub/sub |
| `events` | Inbound credits and security events (Redis only: the bank keeps the source events) |
| `cases`, `watchlist`, `profile`, `audit` | Case queue, mule watchlist, 30-day daily stats, hash-chained audit log |
| `config` | Security (JWT checks), problem details, rate and size limits, Redis client, JSON log layout |

`transferguard-simulator` is the second deployable: it seeds history and generates labelled traffic.

## API

A Postman collection covering all 19 operations, with automatic tokens from the mock issuer and test
assertions, is in [`postman/`](postman/): import `TransferGuard.postman_collection.json` and
`TransferGuard-local.postman_environment.json`, then run it in order. `make postman` runs it with newman.

The contract is [`docs/openapi.yaml`](docs/openapi.yaml) (OpenAPI 3.1, 19 operations under `/api/v1`);
Swagger UI serves that file. `OpenApiContractTest` fails if any operation's method, path or scope differs
from the controllers, and `SpringdocContractIT` compares it with springdoc's view of the running app.
Errors are RFC 9457 problem details with a `traceId`. Amounts are strings with two decimals.

## Security

Every call needs an OAuth2 access token; each endpoint checks exactly one `transferguard/*` scope. The
resource server checks the JWKS signature, an issuer allow-list, `token_use=access`, a `client_id`
allow-list, expiry and a 15-minute maximum lifetime. Locally a mock issuer issues Cognito-shaped tokens,
and the `local` profile trusts both `localhost:8090` and `mock-oauth2:8080` as issuers (the mock stamps
`iss` with the host the token was requested from). Requests are rate-limited per `client_id` and capped
at 16 KB. Account numbers are masked in logs and never used as metric labels or span attributes (PDPA).

See the [threat model](docs/threat-model.md) for the STRIDE analysis and the control behind each threat.

## Tests

| Layer | What | Run |
|---|---|---|
| Unit | Every rule edge case from the plan, `DecisionPolicy`, RFC 8785 canonical JSON, the audit hash against an independent vector, JWT validators | `make build` |
| Contract | The contract example → example response (golden); controllers ↔ `docs/openapi.yaml` (method, path, scope) | `make build` |
| Web slice | 401/403/400/409/413 problem shapes, replay header, unknown fields | `make build` |
| Architecture | ArchUnit: rules never import features, rules do no I/O, controllers never touch repositories | `make build` |
| Integration | Testcontainers PostgreSQL 16 + Redis 7: golden example over HTTP, 20 parallel retries → 1 row and 1 velocity count, R1 window edges, outcomes, mule ring, watchlist, case lifecycle, audit tamper detection, app role cannot edit audit rows, rule tuning with `If-Match`, degraded mode, the Lua script | `make it` |
| Mutation | PIT on `rules.impl` and `DecisionPolicy`, gate 70% | `make mutation` |

CI/CD lives in `.github/workflows/`:

| Workflow | Runs | What |
|---|---|---|
| `pretest.yml` | called by CI/CD, or by hand | Unit, contract, web-slice and ArchUnit tests on JDK 21; PIT mutation gate |
| `ci.yml` (CI/CD) | every PR and push to `main` | Pre-test → Testcontainers integration tests with the 80% coverage gate (and SonarCloud if `SONAR_TOKEN` is set), in parallel with CodeQL, gitleaks and Trivy → on `main`: push both images to GHCR with Jib, Trivy image scan, CycloneDX SBOMs, keyless cosign signatures → post-test |
| `posttest.yml` | called by CI/CD and nightly, or by hand | Starts the stack with Docker Compose, seeds history, runs the five scenarios and `scripts/smoke-test.sh`; optional k6 smoke. On PRs it builds the images from the commit; on `main` it tests the exact published digests |
| `nightly.yml` | 02:00 Kuala Lumpur time | Post-test with the k6 load smoke (so R4 fires too) |

Locally, `make smoke` runs the same smoke test against `make up`.

## Performance and accuracy

- **Load:** `make load` runs k6 at a constant 200 requests per second for 5 minutes (90% normal, 10%
  scam) with thresholds p95 < 100 ms, p99 < 250 ms and < 0.1% errors.
- **Chaos:** `make chaos` adds 200 ms of Redis latency at minute 2 and cuts Redis at minute 3, once per
  fail mode, and writes the numbers to `chaos/results/` for the [postmortem](docs/postmortem.md).
- **Precision and recall:** `make sim` prints a per-rule table from labelled synthetic traffic.

_Results: not yet recorded. Publish the k6 table, hardware and a Grafana screenshot here after a run._
The data is synthetic, so read precision and recall as a regression baseline, not real-world accuracy.

## Deploying

- **Kubernetes:** `deploy/helm/transferguard` has 3 replicas, a PDB (`minAvailable: 2`), an HPA at 70%
  CPU, probes with readiness on PostgreSQL but not Redis, a restricted `securityContext`, a NetworkPolicy,
  and a pre-install/pre-upgrade Job that runs Flyway as the migrator role (`MigrateMain`).
  `values-kind.yaml` is for kind.
- **AWS:** the same image on ECS Fargate or EKS, with RDS PostgreSQL (Multi-AZ), ElastiCache Redis (TLS,
  ACL users), Cognito and Secrets Manager. The `prod` profile trusts exactly one Cognito issuer and turns
  Swagger UI off.

## Decisions and assumptions

ADRs: [001 rules before ML](docs/adr/001-rules-before-machine-learning.md) ·
[002 Redis feature store](docs/adr/002-redis-feature-store.md) ·
[003 fail degraded](docs/adr/003-fail-degraded.md) ·
[004 Spring Boot 3](docs/adr/004-spring-boot-3.md) ·
[005 modular monolith](docs/adr/005-modular-monolith.md).
The original [high-level design](docs/design/high-level-design.md) and
[implementation plan](docs/design/implementation-plan.md) are in `docs/design/`.

Choices made where the design left room:

- **Weights for R1, R5 and R7** were not specified. R1 = 40, so a burst alone is challenged; R5 = 50, so
  pass-through plus a large new payee (R2) holds; R7 = 70, so a watchlisted payee alone holds.
- **Reason order** is highest points first, so the example response in `docs/openapi.yaml` lists R2, R3,
  R6, R4 (same four reasons, same score of 80).
- **OPEN fail mode** allows without running any rule; **CLOSED** runs the PostgreSQL rules and challenges
  at minimum.
- **History seeding** writes straight to PostgreSQL and Redis, because the API only accepts requests
  timed within 5 minutes of now.
- **Tracing** uses Spring Boot's OpenTelemetry starter (Micrometer bridge, OTLP to Tempo) rather than the
  OTel Java agent, with spans `screening.features`, `screening.rules` and `screening.persist`.
- `Features` and `FeatureNeeds` live in `rules` (not `features`), so rules depend on nothing that does I/O.

Open questions, assumed until tested with a bank:

- Retention: 7 years for screenings, cases and the audit log is a placeholder pending Bank Negara
  Malaysia record-keeping requirements.
- The outcome callback is treated as required: without it, payees never become known.
- Watchlist sharing between banks (for example through the National Scam Response Centre) is not built.
- 100 ms at p95 is assumed to fit the rest of the bank's transfer path.

Not built yet: S3 Object Lock upload of the hourly audit head (it is logged), four-eyes rule approval,
multi-bank tenancy, and the AWS CDK/CloudFormation stack.

## License

MIT
