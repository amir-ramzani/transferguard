# ADR 005: A synchronous modular monolith

**Status:** Accepted · **Date:** 2024-11-02

## Context
The system has a single latency-critical path (screening) and several low-volume administrative
flows. It must run on a laptop with one command and be easy to reason about in an interview.

## Decision
One stateless Spring Boot service, packaged by feature (`screening`, `rules`, `features`, `events`,
`cases`, `watchlist`, `profile`, `audit`, `config`). ArchUnit enforces the boundaries:
- `rules` never depends on `features`: rules declare `FeatureNeeds`; `features` fulfils them.
- Rule implementations depend only on `rules`, `common` and the JDK (no I/O).
- Controllers never touch repositories.
- `screening.api` (the public DTOs) is a leaf.
Cross-module calls that would create a cycle go through interfaces owned by the caller
(`RuleChangeBus`, `ScreeningLookup`).

## Consequences
- One deployable, one database transaction per screening, no message broker to operate.
- Scaling is horizontal: pods hold no state.
- A later split (for example the case API into its own service) follows the existing module seams.
