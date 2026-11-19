# ADR 004: Spring Boot 4 and Java 21

**Status:** Accepted · **Date:** 2026-11-19

## Context
The original specification named Spring Boot 3. Open-source support for 3.5, the last 3.x line,
ended on 30 June 2026. A security reviewer checks the support status of the framework first.

## Decision
Build on **Spring Boot 4.1** (Spring Framework 7, Spring Security 7, Jackson 3) with **Java 21**, and
run CI on Java 21 and 25.

## Consequences and what changed on the way
- Modular starters: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway` (Flyway is no longer
  auto-configured from `flyway-core` alone), `spring-boot-starter-security-oauth2-resource-server`,
  `spring-boot-starter-opentelemetry`.
- Test slices moved packages, e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`;
  health contributors live in `org.springframework.boot.health.contributor`.
- Jackson 3 lives in `tools.jackson.*` (annotations stay in `com.fasterxml.jackson.annotation`);
  `JsonNode.asText()` became `asString()`; exceptions are unchecked.
- `org.springframework.lang.Nullable` is deprecated in favour of JSpecify.
- springdoc-openapi 3.x is the line that supports Boot 4.
- Virtual threads (`spring.threads.virtual.enabled=true`) handle request concurrency.
