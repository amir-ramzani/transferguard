package io.github.nulled.transferguard.features;

import java.time.Instant;

/** Published when the Redis circuit breaker closes again, so the rebuild job can run. */
public record RedisRecoveredEvent(Instant outageStartedAt) {}
