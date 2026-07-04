# TransferGuard — High-Level Design

Nov 19, 2026

## 1. Overview

TransferGuard (codename Pengawal) screens outgoing bank transfers before the money leaves the account. Each transfer gets a decision, **ALLOW**, **CHALLENGE** or **HOLD**, with human-readable reasons, in under 100 ms at p95. It also spots mule accounts that receive money from many senders and pass it on quickly.

**Yes, it runs fully on a laptop.** One `docker compose up` starts everything, with no cloud account needed. The only cloud dependency in the design is AWS Cognito for tokens. Locally, a mock OAuth2 server issues tokens with the same claims and scopes, so the application code is identical and only the issuer URL changes (section 8).

| In scope | Out of scope |
| --- | --- |
| Screening API, seven tunable rules, decision bands | Real core banking or payment network (a simulator stands in) |
| Inbound credit, security and transfer-outcome events | Machine learning (a stretch goal) |
| Analyst case queue, watchlist, rule tuning | Customer-facing UI (Swagger UI only) |
| Hash-chained audit log | Customer notifications, OTP delivery |
| Metrics, tracing, load and chaos testing | Multi-region deployment |

This document describes the system's structure and behaviour. The build order, schema detail and rule logic are in the [implementation plan](implementation-plan.md). The API contract is the `openapi.yaml` file that goes with this document.

## 2. Architecture

TransferGuard is one stateless Spring Boot service, a modular monolith, that sits between the bank's systems and two data stores. Scaling it means running more pods; no pod holds state.

[embedded diagram, not exported: TransferGuard architecture · 3 callers, 5 modules, 2 data stores]

The bank's systems and analysts call one API. Inside it, only the Screening and Rules modules sit on the per-transfer path. PostgreSQL holds the record, Redis holds fast, rebuildable features, and the API fetches token signing keys from the identity provider.

## 3. Screening flow

One screening makes three short Redis calls and one PostgreSQL transaction. The rules themselves run in memory and touch no network.

[embedded diagram, not exported: Screening sequence · 6 messages, 1 in-memory decision]

The idempotency claim comes first, so a replayed request never reaches the rules. The decision is recorded in PostgreSQL before the response is sent. If PostgreSQL is down the caller gets a 503, never an unrecorded decision. If Redis is down the API keeps answering in degraded mode (implementation plan, section 8).

## 4. Other flows

All other flows are simple writes. Each one updates the record in PostgreSQL first, then updates Redis features or caches. Every administrative change also adds an audit row in the same transaction.

| Flow | Caller and endpoint | PostgreSQL | Redis | Audit row |
| --- | --- | --- | --- | --- |
| Inbound credit | Bank core → `POST /events/inbound-credits` (idempotent on `creditId`) | none | `ZADD tg:{acct}:in` | No |
| Device change or password reset | Bank identity service → `POST /events/security` | none | `HSET tg:{acct}:sec` | No |
| Transfer outcome | Bank transfer service → `POST /screenings/{id}/outcome` | `screening.outcome`; on COMPLETED, upsert `account_daily_stats` | On COMPLETED: `SADD payees`, `ZADD outdone`, `DEL avg` | No |
| Case assignment or resolution | Analyst → `PATCH /cases/{id}`, `POST /cases/{id}/notes` | `fraud_case`, `case_note` | none | Yes |
| Rule change | Analyst → `PUT /rules/{code}` with `If-Match` | `rule` (optimistic lock on version) | `PUBLISH tg:rules:changed`; every pod reloads its cache | Yes |
| Engine settings change | Analyst → `PUT /settings/engine` | `engine_setting` | `PUBLISH tg:rules:changed` | Yes |
| Watchlist change | Analyst → `PUT` or `DELETE /watchlist/{accountNo}` | `watchlist_entry` | `SADD` or `SREM tg:watchlist` | Yes |

**Why events skip PostgreSQL.** Inbound credits and security events only feed short time windows (60 and 30 minutes). Storing them in PostgreSQL would add write load without helping any rule. The bank's own systems remain the record for them. If Redis restarts, R5 and R6 work at reduced strength for one window. That cost is visible on the dashboard and covered in the chaos test.

**Case lifecycle.** A HOLD opens a case as `OPEN`. An analyst assigns it (`assignee` set), then resolves it as `CONFIRMED_FRAUD` or `FALSE_POSITIVE`. Resolution is final. A correction becomes a new note, not an edit. When a case is confirmed as fraud, the analyst can add the payee to the watchlist in the same request (`addPayeeToWatchlist: true`), which closes the loop for R7.

## 5. Data architecture

PostgreSQL is the system of record. Redis holds only short-lived features and caches that can be lost and rebuilt. No business fact exists only in Redis except the raw event windows, which the bank also keeps.

| Data | Store | Owner component | Retention | Rebuildable? |
| --- | --- | --- | --- | --- |
| Screenings and reasons | PostgreSQL `screening`, `screening_reason` | Screening | 7 years (typical bank record keeping; partition by month at scale) | Is the record |
| Fraud cases and notes | PostgreSQL `fraud_case`, `case_note` | Cases | 7 years | Is the record |
| Rules and engine settings | PostgreSQL `rule`, `engine_setting` | Rules | Current version plus full history in `audit_log` | Is the record |
| Watchlist | PostgreSQL `watchlist_entry`, mirrored to Redis `tg:watchlist` | Watchlist | Until removed | Redis copy rebuilt at startup |
| Daily account stats | PostgreSQL `account_daily_stats` | Profile | 35 days (nightly purge) | From `screening` |
| Audit trail | PostgreSQL `audit_log`; hourly head hash to S3 with Object Lock | Audit | 7 years; append-only | Is the record |
| Velocity, outbound and known-payee features | Redis sorted sets and sets | Features | The rule window (minutes); payees 400 days | From `screening` |
| Inbound credits, security events | Redis sorted set and hash | Events | 60 min and 30 days | No, the bank holds the source events |
| Idempotency keys | Redis strings | Screening | 24 h | The unique constraint in PostgreSQL is the backstop |
| Rule cache | In-memory Caffeine in each pod | Rules | 60 s, or until a pub/sub reload | From `rule` |

**Personal data.** Account numbers and device IDs are personal data under Malaysia's PDPA. They are stored in full only in PostgreSQL and Redis, inside the private network. They are masked in logs and never used as metric labels or trace attributes. The retention periods above are placeholders until a bank's compliance team confirms them (section 10).

## 6. Security architecture

Every call carries an OAuth2 access token (JWT), and each endpoint needs exactly one scope. Machines and people use different OAuth flows, but the token format and the checks are the same.

| Caller | OAuth2 flow | Scopes granted |
| --- | --- | --- |
| Bank transfer service (the simulator, locally) | Client credentials | `screening.write`, `screening.read`, `events.write` |
| Bank core and identity services | Client credentials | `events.write` |
| Fraud analyst | Authorization code with PKCE | `cases.read`, `cases.write`, `screening.read` |
| Fraud lead or rules admin | Authorization code with PKCE | The analyst scopes plus `rules.admin` and `audit.read` |
| Prometheus | None (management port, not exposed outside the cluster) | n/a |

All scopes carry the resource-server prefix `transferguard/`, for example `transferguard/screening.write`. That is Cognito's custom-scope format, and the local mock issuer is configured to issue the same strings.

**Token checks** happen in the Spring Security resource server: signature against the issuer's JWKS, `iss`, expiry, `token_use = access` and an allow-list of `client_id`s. Cognito access tokens have no `aud` claim, so `client_id` takes its place. Scopes become `SCOPE_transferguard/...` authorities, checked with `@PreAuthorize`.

**Trust boundaries** (drawn in section 2):

1. **Bank network to TransferGuard.** TLS terminates at the load balancer (locally, plain HTTP on localhost). Every request is authenticated, rate-limited per `client_id` and size-limited to 16 KB.
2. **TransferGuard to its data stores.** These are private-subnet only. PostgreSQL uses separate app and migrator roles; the app role cannot update or delete audit rows. Redis uses ACL users with TLS in AWS.
3. **Operators.** Actuator and Prometheus scraping run on a separate management port that the load balancer doesn't route. Swagger UI is turned off in production.

The full STRIDE threat model and the supply-chain controls (SBOM, cosign, Trivy) are in section 9 of the implementation plan.

## 7. Non-functional requirements

The latency and throughput targets are proven by the k6 test; the availability and recovery targets are proven by the chaos test. The table lists portfolio targets, not a bank's real service-level agreement.

| Requirement | Target | How it is met | How it is proven |
| --- | --- | --- | --- |
| Screening latency | p95 < 100 ms, p99 < 250 ms | One Redis round trip, rules in memory, one PostgreSQL transaction, virtual threads | k6, plus a Prometheus alert |
| Throughput | 200 requests per second sustained on one laptop; scales out horizontally | Stateless pods; state is in PostgreSQL and Redis | k6 constant arrival rate |
| Availability | Screening keeps answering while Redis is down | Degraded mode behind a circuit breaker; readiness doesn't depend on Redis | Chaos test with Toxiproxy |
| Correctness under retries | A retried request never changes the result or a velocity count | Redis `SET NX`, transaction ID as the sorted-set member, unique constraint | Concurrency integration test |
| Recovery point objective (RPO) | 0 for screenings and cases; up to one rule window for Redis features | Records commit to PostgreSQL before the response; RDS Multi-AZ in AWS | Postmortem data |
| Recovery time objective (RTO) | Under 1 minute to full-strength features after Redis returns | Rebuild job from `screening` when the breaker closes | Chaos test timeline |
| Explainability | Every decision lists every rule that fired, with points and a message | `screening_reason` rows | Contract test |
| Auditability | Any change to rules, settings, watchlist or cases can be traced and tamper is detectable | Hash-chained `audit_log`, verify endpoint | Integration test that edits a row |
| Configurability | Tuning takes effect in all pods within 60 s, with no redeploy | Rules as data, pub/sub reload, cache expiry | Integration test |
| Observability | Any screening can be traced end to end | OpenTelemetry spans, JSON logs with `traceId` | Grafana and Tempo |
| Portability | Runs the same locally and in AWS | Twelve-factor config; only URLs and secrets change | `docker compose` and Helm |

## 8. Deployment views

The same container image runs locally and in AWS. Only configuration changes: the profile, the issuer URL, the connection strings and where secrets come from.

| Component | Local (Docker Compose) | AWS |
| --- | --- | --- |
| TransferGuard API | `transferguard` container, profile `local` | ECS Fargate service (or EKS with the Helm chart), 2 or more tasks behind an Application Load Balancer |
| Identity provider | `ghcr.io/navikt/mock-oauth2-server` | Amazon Cognito user pool with a resource server and custom scopes |
| PostgreSQL 16 | `postgres:16` container with a named volume | Amazon RDS for PostgreSQL, Multi-AZ |
| Redis 7 | `redis:7` container, reached through Toxiproxy | Amazon ElastiCache for Redis OSS with TLS and ACL users |
| Secrets | `.env` file, git-ignored (`.env.example` is committed) | AWS Secrets Manager |
| Metrics and dashboards | Prometheus and Grafana containers | Amazon Managed Prometheus and Grafana, or the same containers on ECS |
| Traces | Grafana Tempo container (OTLP) | AWS X-Ray through the ADOT collector, or Tempo |
| Traffic | `simulator` container | `simulator` task, run on demand |

**Compose profiles** keep the laptop light:

- `core` (default): API, PostgreSQL, Redis, Toxiproxy, mock OAuth.
- `observability`: adds Prometheus, Grafana and Tempo.
- `sim`: adds the traffic simulator.

Estimate: `core` needs about 2 GB of memory; everything together, about 5 GB. Give Docker at least 6 GB.

| Service | URL on your machine |
| --- | --- |
| API and Swagger UI | http://localhost:8080/swagger-ui.html |
| Actuator (management port) | http://localhost:8081/actuator/health |
| Mock OAuth issuer | http://localhost:8090/transferguard |
| PostgreSQL | localhost:5432 |
| Redis (through Toxiproxy) | localhost:6379 (Toxiproxy API on 8474) |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

**Run it locally:**

1. Install Docker (Docker Desktop, Rancher Desktop or Podman) and JDK 21.
2. `git clone …/transferguard && cd transferguard && cp .env.example .env`
3. `make up`. This builds the image with Jib and runs `docker compose --profile observability up -d`. Flyway migrates the schema on start.
4. Open Swagger UI, click **Authorize**, and use client ID `local-admin` with any secret. The mock issues a token with every scope.
5. `make demo` seeds 30 days of history and runs the five scenarios. Watch the decisions in Swagger (`GET /cases?status=OPEN`) and in Grafana.
6. `make down` stops everything; `make clean` also removes the volumes.

**A local gotcha to design for.** The mock issuer stamps `iss` with the host name the token was requested from. Tokens fetched from your browser say `localhost:8090`, and tokens fetched by the simulator inside Docker say `mock-oauth2:8080`. The `local` profile therefore trusts both issuer URLs and fetches signing keys from the internal one. The `prod` profile trusts exactly one Cognito issuer.

## 9. API overview

The contract is `openapi.yaml` (OpenAPI 3.1, 19 operations under `/api/v1`). It passes the Redocly and openapi-spec-validator checks. Commit it at `docs/openapi.yaml` and add a contract test that compares it with the springdoc output, so the code and the contract can't drift apart.

| Method | Path | Purpose | Scope (`transferguard/…`) |
| --- | --- | --- | --- |
| POST | `/screenings` | Screen a transfer (idempotent on `transactionId`) | `screening.write` |
| GET | `/screenings/{screeningId}` | Fetch a screening with its reasons | `screening.read` |
| POST | `/screenings/{screeningId}/outcome` | Report COMPLETED, CHALLENGE\_FAILED or CANCELLED | `screening.write` |
| POST | `/events/inbound-credits` | Incoming credit (R5) | `events.write` |
| POST | `/events/security` | Device change or password reset (R6) | `events.write` |
| GET | `/cases` | Case queue, filtered by status and assignee | `cases.read` |
| GET | `/cases/{caseId}` | Case with its screening and notes | `cases.read` |
| PATCH | `/cases/{caseId}` | Assign or resolve | `cases.write` |
| POST | `/cases/{caseId}/notes` | Add a note | `cases.write` |
| GET | `/rules` | List rules | `rules.admin` |
| GET, PUT | `/rules/{code}` | View or tune a rule (`If-Match` required) | `rules.admin` |
| GET, PUT | `/settings/engine` | Decision bands and fail mode | `rules.admin` |
| GET | `/watchlist` | List reported mule accounts | `rules.admin` |
| PUT, DELETE | `/watchlist/{accountNo}` | Add or remove an account | `rules.admin` |
| GET | `/audit` | Audit entries, filterable by entity | `audit.read` |
| GET | `/audit/verify` | Recompute the hash chain | `audit.read` |

**Contract choices that differ from the original spec:**

- **Amounts are strings** (`"4800.00"`, two decimal places). No client then parses money as a binary float. JSON numbers lose precision in JavaScript and other clients.
- **The watchlist uses `PUT`, not `POST`.** Adding the same account twice is naturally idempotent.
- **Rule and settings updates need `If-Match`.** Two analysts tuning the same rule get a `412` instead of a silent overwrite.
- **The example HOLD response scores 80**, the corrected total.
- **Errors are RFC 9457 problem details** with a `traceId`, so a support call can jump straight to the trace.

## 10. Key decisions and open questions

Five decisions shape the design. Each one gets an architecture decision record (ADR) in `docs/adr/`.

| ADR | Decision | Main alternative rejected | Why |
| --- | --- | --- | --- |
| 001 | Rules before machine learning | A scoring model from day one | Regulators and analysts need to see why a transfer was held; there is no labelled data yet |
| 002 | Redis as the feature store | Querying PostgreSQL history on every request | One round trip keeps p95 under 100 ms; windowed counts are what sorted sets are built for |
| 003 | Fail degraded when Redis is down | Fail open (fraud loss) or fail closed (every customer challenged) | Keeps the rules that need only PostgreSQL, raises friction only on larger amounts, and makes the trade-off a setting |
| 004 | Spring Boot 4 and Java 21 | Spring Boot 3.5 | 3.x no longer gets free security patches; a security reviewer will check this |
| 005 | A synchronous modular monolith | Microservices, or Kafka between components | One deployable is easier to run locally and to reason about; module boundaries are enforced by ArchUnit, so a split stays possible later |

**Open questions** (state your assumption in the README until you can test it with a bank):

- [ ] Retention periods: 7 years is a placeholder. What do Bank Negara Malaysia's record-keeping rules require for screening records and audit logs?
- [ ] Does the bank's transfer service wait for a CHALLENGE to resolve, or does it treat CHALLENGE as "send an OTP and continue"? This changes whether the outcome callback is required or optional.
- [ ] Should watchlist data be shared between banks (as through the National Scam Response Centre), and how would a shared feed be authenticated?
- [ ] Is 100 ms at p95 the right budget, given the rest of the bank's transfer path?
