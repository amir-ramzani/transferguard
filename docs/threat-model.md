# Threat model (STRIDE)

Scope: the TransferGuard API, its PostgreSQL and Redis stores, the identity provider (Cognito, or the
mock issuer locally) and the operators. Trust boundaries are those in the high-level design, section 6:
bank network → API; API → data stores; operators → management port.

| # | STRIDE | Threat | Control | Where it lives |
|---|---|---|---|---|
| S1 | Spoofing | Forged or stolen access token | JWKS signature check; `iss` allow-list; `token_use=access`; `client_id` allow-list (Cognito access tokens have no `aud`); expiry with 30 s skew; tokens living longer than 15 min rejected | `SecurityConfig`, `JwtValidators`, `JwtValidatorsTest` |
| S2 | Spoofing | A caller acting with another caller's rights | Exactly one scope per endpoint, checked with `@PreAuthorize`; machine clients use client credentials, analysts authorization code + PKCE | controllers, `OpenApiContractTest` checks every operation's scope |
| T1 | Tampering | An insider edits rules to let fraud through | Every rule and setting change is audited in the same transaction; optimistic locking (`If-Match`) | `RuleConfigService`, `AuditWriter` |
| T2 | Tampering | Someone edits or deletes audit rows | Hash chain (SHA-256 over RFC 8785 JSON); writers serialised by `pg_advisory_xact_lock`; the app role has no `UPDATE`/`DELETE`/`TRUNCATE` on `audit_log`; nightly verification with an alert; hourly head hash (S3 Object Lock in AWS) catches deletion of the newest rows | `V10__app_role_grants.sql`, `AuditVerifier`, `AuditChainMonitor` |
| T3 | Tampering | Replayed or modified requests change a decision or a count | Idempotency on `transactionId` (Redis `SET NX`, unique constraint backstop); request hash comparison returns 422 on a changed body; velocity members are transaction IDs | `ScreeningService`, `IdempotencyStore`, `screen_features.lua` |
| T4 | Tampering | `requestedAt` forged to dodge R4 | Rejected if more than 5 minutes from server time | `ScreeningService.validate` |
| R1 | Repudiation | An analyst denies resolving a case or changing a rule | Audit rows carry the JWT subject; resolution is final, corrections are new notes | `CaseService`, `AuditWriter` |
| I1 | Information disclosure | Account numbers leak through logs, metrics or traces | Logback layout masks `1402-****-3456`; no account numbers in metric labels or span attributes | `MaskingJsonLayout`, `Pii`, `ScreeningService` |
| I2 | Information disclosure | Fraudsters learn the thresholds from responses | Reason codes go only to the bank's backend (machine scope), never to customers; alert on HOLD-rate spikes | API design, `alerts.yml` |
| I3 | Information disclosure | Internals exposed | Actuator only on the management port (`health`, `prometheus`); Swagger UI off in `prod`; problem details never include stack traces | `application*.yml`, `GlobalExceptionHandler` |
| D1 | Denial of service | Request floods | Per-`client_id` token bucket (Bucket4j) → 429 with `Retry-After`; HPA on CPU | `RateLimitFilter`, Helm chart |
| D2 | Denial of service | Oversized bodies | 16 KB limit, by `Content-Length` and by counting chunked bodies → 413 | `RequestSizeFilter` |
| D3 | Denial of service | Redis slow or down takes screening down | 50 ms timeout, circuit breaker, degraded mode; readiness excludes Redis | `RedisGateway`, ADR 003 |
| D4 | Denial of service | PostgreSQL down | 250 ms connection timeout → 503, never an unrecorded decision; readiness fails so traffic moves to healthy pods | `GlobalExceptionHandler`, readiness group |
| E1 | Elevation of privilege | App credentials used to change the schema or rewrite history | Separate migrator and app roles; migrations run only in a pre-install Job | `infra/postgres/init.sh`, `MigrateMain`, Helm `migrate-job.yaml` |
| E2 | Elevation of privilege | A compromised container | Distroless JRE, non-root (65532), read-only root file system, all capabilities dropped, NetworkPolicy egress allow-list | Jib config, Helm chart |
| E3 | Elevation of privilege | Vulnerable or tampered dependencies and images | Renovate; Trivy (file system and image, fail on HIGH/CRITICAL); CodeQL; gitleaks in CI and pre-commit; CycloneDX SBOM; keyless cosign signatures | `.github/workflows/ci.yml` |

## Out of scope / accepted risks
- Customer-facing channels, OTP delivery and the bank's own transfer service.
- Multi-region failover.
- Rate limits are per pod; a coordinated flood across many clients relies on the load balancer/WAF.
- The four-eyes approval for rule changes (a change stays `PENDING` until a second analyst approves)
  is designed but not built.
