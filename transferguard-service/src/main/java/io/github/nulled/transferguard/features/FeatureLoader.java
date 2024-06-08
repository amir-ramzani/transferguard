package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.rules.FailMode;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Transfer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Loads features from Redis, falling back to PostgreSQL (or to nothing, in fail-open) when Redis is unavailable. */
@Component
public class FeatureLoader {

    private static final Logger log = LoggerFactory.getLogger(FeatureLoader.class);

    public enum Mode { NORMAL, DEGRADED }

    public record Loaded(Features features, Mode mode) {
        public boolean degraded() {
            return mode == Mode.DEGRADED;
        }
    }

    private final RedisFeatureLoader redis;
    private final PostgresFeatureLoader postgres;
    private final Timer normalTimer;
    private final Timer degradedTimer;

    public FeatureLoader(RedisFeatureLoader redis, PostgresFeatureLoader postgres, MeterRegistry meters) {
        this.redis = redis;
        this.postgres = postgres;
        this.normalTimer = Timer.builder("transferguard.feature.load").tag("mode", "normal")
                .publishPercentileHistogram().register(meters);
        this.degradedTimer = Timer.builder("transferguard.feature.load").tag("mode", "degraded")
                .publishPercentileHistogram().register(meters);
    }

    public Loaded load(Transfer transfer, FeatureNeeds needs, Instant now, FailMode failMode) {
        long start = System.nanoTime();
        try {
            Features f = redis.load(transfer, needs, now);
            normalTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            return new Loaded(f, Mode.NORMAL);
        } catch (RedisUnavailableException e) {
            log.debug("Screening degraded: {}", e.getMessage());
        }
        long fallbackStart = System.nanoTime();
        Features f = failMode == FailMode.OPEN
                ? new Features(Set.of(), 0, null, null, false, null, null, false)
                : postgres.load(transfer, needs, now);
        degradedTimer.record(System.nanoTime() - fallbackStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        return new Loaded(f, Mode.DEGRADED);
    }
}
