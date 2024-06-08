package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.config.TransferGuardProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The only way into Redis on the request path. Every call runs behind a Resilience4j circuit
 * breaker that opens at 50% failures over 20 calls and probes again after 10 seconds, so a slow or
 * dead Redis costs at most one 50 ms timeout per request before screening degrades.
 */
@Component
public class RedisGateway {

    private static final Logger log = LoggerFactory.getLogger(RedisGateway.class);

    private final RedisClient fastClient;
    private final RedisClient bulkClient;
    private final CircuitBreaker breaker;
    private final Duration commandTimeout;
    private final Duration bulkTimeout;
    private final Clock clock;
    private volatile StatefulRedisConnection<String, String> fast;
    private volatile StatefulRedisConnection<String, String> bulk;
    private volatile Instant openedAt;

    public RedisGateway(@Qualifier("fastRedisClient") RedisClient fastClient,
                        @Qualifier("bulkRedisClient") RedisClient bulkClient,
                        TransferGuardProperties props, MeterRegistry meters, ApplicationEventPublisher events,
                        Clock clock) {
        this.fastClient = fastClient;
        this.bulkClient = bulkClient;
        this.commandTimeout = props.redis().commandTimeout();
        this.bulkTimeout = props.redis().bulkTimeout();
        this.clock = clock;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.redis().breakerWindow())
                .minimumNumberOfCalls(props.redis().breakerWindow())
                .failureRateThreshold(props.redis().breakerFailureRate())
                .waitDurationInOpenState(props.redis().breakerWaitOpen())
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.breaker = registry.circuitBreaker("redis");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        breaker.getEventPublisher().onStateTransition(e -> {
            log.warn("Redis circuit breaker {}", e.getStateTransition());
            switch (e.getStateTransition().getToState()) {
                case OPEN -> {
                    if (openedAt == null) {
                        openedAt = clock.instant();
                    }
                }
                case CLOSED -> {
                    Instant since = openedAt == null ? clock.instant() : openedAt;
                    openedAt = null;
                    events.publishEvent(new RedisRecoveredEvent(since));
                }
                default -> { }
            }
        });
    }

    /** Runs {@code op} on the shared connection behind the breaker. */
    public <T> T call(Function<RedisAsyncCommands<String, String>, CompletionStage<T>> op) {
        try {
            return breaker.executeCallable(() -> await(op.apply(fast().async()), commandTimeout.multipliedBy(4)));
        } catch (CallNotPermittedException e) {
            throw new RedisUnavailableException("Redis circuit breaker is open", e);
        } catch (RedisUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisUnavailableException("Redis call failed: " + e.getMessage(), e);
        }
    }

    /** Bulk work (rebuilds, resyncs) on a separate connection with a longer timeout, outside the breaker. */
    public <T> T bulk(Function<RedisAsyncCommands<String, String>, CompletionStage<T>> op) {
        try {
            return await(op.apply(bulkConnection().async()), bulkTimeout.multipliedBy(10));
        } catch (Exception e) {
            throw new RedisUnavailableException("Redis bulk call failed: " + e.getMessage(), e);
        }
    }

    public boolean available() {
        return breaker.getState() != CircuitBreaker.State.OPEN && breaker.getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    private static <T> T await(CompletionStage<T> stage, Duration max) throws Exception {
        try {
            return stage.toCompletableFuture().get(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private StatefulRedisConnection<String, String> fast() {
        StatefulRedisConnection<String, String> c = fast;
        if (c == null) {
            synchronized (this) {
                if (fast == null) {
                    fast = fastClient.connect();
                }
                c = fast;
            }
        }
        return c;
    }

    private StatefulRedisConnection<String, String> bulkConnection() {
        StatefulRedisConnection<String, String> c = bulk;
        if (c == null) {
            synchronized (this) {
                if (bulk == null) {
                    bulk = bulkClient.connect();
                }
                c = bulk;
            }
        }
        return c;
    }

    @PreDestroy
    void close() {
        if (fast != null) {
            fast.close();
        }
        if (bulk != null) {
            bulk.close();
        }
    }
}
