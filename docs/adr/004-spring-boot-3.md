# ADR 004: Spring Boot 3 and Java 21

**Status:** Accepted · **Date:** 2024-11-02

## Context
The service needs a mainstream, well-supported stack that a bank's platform team can run and patch:
OAuth2 resource-server support, Flyway, Actuator health groups and Micrometer out of the box.

## Decision
Build on **Spring Boot 3** (Spring Framework 6, Spring Security 6, Jackson 2) with **Java 21**, the
current LTS.

## Consequences
- Virtual threads (`spring.threads.virtual.enabled=true`, Boot 3.2+) handle request concurrency
  without a reactive stack.
- `JdbcClient` and `RestClient` (Framework 6.1) keep data access and HTTP calls simple and explicit.
- Tracing goes through Micrometer Tracing with the OpenTelemetry bridge and the OTLP exporter.
- Flyway 10 (Boot 3.3+) needs `flyway-database-postgresql` on the classpath.
- Revisit when Spring Boot 4 is released; the open-source support window for each 3.x line is short.
