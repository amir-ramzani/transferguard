package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.RuleConfigService;
import io.github.nulled.transferguard.rules.RuleEngine;
import io.lettuce.core.Range;
import io.lettuce.core.ZAddArgs;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/** Writes the Redis features that events, outcomes and the watchlist feed. */
@Component
public class FeatureRecorder {

    static final Duration PAYEES_TTL = Duration.ofDays(400);
    static final Duration SECURITY_TTL = Duration.ofDays(30);

    private final RedisGateway redis;
    private final RuleConfigService rules;
    private final RuleEngine engine;

    public FeatureRecorder(RedisGateway redis, RuleConfigService rules, RuleEngine engine) {
        this.redis = redis;
        this.rules = rules;
        this.engine = engine;
    }

    /** Inbound credit for R5. Idempotent: the creditId is part of the sorted-set member. */
    public void recordInboundCredit(String account, String creditId, String sender, BigDecimal amount,
                                    Instant receivedAt, Instant now) {
        String key = Keys.in(account);
        long retention = passThroughRetention().toMillis();
        String member = creditId + "|" + sender + "|" + amount.toPlainString();
        redis.call(c -> CompletableFuture.allOf(
                c.zremrangebyscore(key, Range.from(Range.Boundary.unbounded(),
                        Range.Boundary.excluding((double) (now.toEpochMilli() - retention)))).toCompletableFuture(),
                c.zadd(key, ZAddArgs.Builder.nx(), (double) receivedAt.toEpochMilli(), member).toCompletableFuture(),
                c.pexpire(key, retention).toCompletableFuture()));
    }

    /** Device change or password reset for R6. */
    public void recordSecurityEvent(String account, String type, String deviceId, Instant occurredAt) {
        String key = Keys.security(account);
        Map<String, String> fields = "DEVICE_CHANGED".equals(type)
                ? Map.of("deviceId", deviceId, "deviceChangedAt", Long.toString(occurredAt.toEpochMilli()))
                : Map.of("pwdResetAt", Long.toString(occurredAt.toEpochMilli()));
        redis.call(c -> CompletableFuture.allOf(
                c.hset(key, fields).toCompletableFuture(),
                c.pexpire(key, SECURITY_TTL.toMillis()).toCompletableFuture()));
    }

    /** A completed transfer: the payee becomes known, R5's outbound sum grows, the cached average is stale. */
    public void recordCompleted(String account, String payee, String txnId, BigDecimal amount, Instant occurredAt) {
        long retention = passThroughRetention().toMillis();
        String out = Keys.outDone(account);
        String payees = Keys.payees(account);
        redis.call(c -> CompletableFuture.allOf(
                c.sadd(payees, payee).toCompletableFuture(),
                c.pexpire(payees, PAYEES_TTL.toMillis()).toCompletableFuture(),
                c.zadd(out, ZAddArgs.Builder.nx(), (double) occurredAt.toEpochMilli(),
                        txnId + "|" + amount.toPlainString()).toCompletableFuture(),
                c.pexpire(out, retention).toCompletableFuture(),
                c.del(Keys.average(account)).toCompletableFuture()));
    }

    public void watchlistAdd(String account) {
        redis.call(c -> c.sadd(Keys.WATCHLIST, account));
    }

    public void watchlistRemove(String account) {
        redis.call(c -> c.srem(Keys.WATCHLIST, account));
    }

    /** In/outdone keep at least 60 minutes, or the R5 window if an analyst made it longer. */
    Duration passThroughRetention() {
        FeatureNeeds needs = engine.needs(rules.activeRules().stream()
                .filter(r -> r.needs().needs(Feature.PASS_THROUGH)).toList());
        Duration window = needs.passThroughWindow();
        return window == null || window.compareTo(RedisFeatureLoader.MIN_EVENT_RETENTION) < 0
                ? RedisFeatureLoader.MIN_EVENT_RETENTION : window;
    }
}
