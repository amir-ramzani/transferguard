package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.config.TransferGuardProperties;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds Redis features from PostgreSQL, the system of record: at startup and whenever the Redis
 * circuit breaker closes. Without it R1 under-counts for one window after an outage, and every payee
 * looks new after Redis loses its data.
 */
@Component
public class FeatureRebuilder {

    private static final Logger log = LoggerFactory.getLogger(FeatureRebuilder.class);
    private static final int BATCH = 500;
    static final Duration REALTIME_LOOKBACK = Duration.ofMinutes(60);

    private final RedisGateway redis;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration startupPayeeLookback;
    private final AtomicBoolean running = new AtomicBoolean();

    public FeatureRebuilder(RedisGateway redis, DataSource dataSource, Clock clock, TransferGuardProperties props) {
        this.redis = redis;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(BATCH);
        this.clock = clock;
        this.startupPayeeLookback = props.redis().payeeStartupLookback();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread.ofVirtual().name("redis-rebuild-startup").start(() -> rebuild(startupPayeeLookback));
    }

    @EventListener
    public void onRecovered(RedisRecoveredEvent event) {
        Duration outage = Duration.between(event.outageStartedAt(), clock.instant()).plusMinutes(1);
        Thread.ofVirtual().name("redis-rebuild-recovery").start(() -> rebuild(outage));
    }

    /** The watchlist mirror is small; resyncing it regularly heals any missed SADD or SREM. */
    @Scheduled(fixedDelayString = "${transferguard.watchlist.resync:PT5M}", initialDelayString = "PT1M")
    public void periodicWatchlistSync() {
        try {
            syncWatchlist();
        } catch (RuntimeException e) {
            log.debug("Watchlist resync skipped: {}", e.getMessage());
        }
    }

    public void rebuild(Duration payeeLookback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long started = System.nanoTime();
        try {
            Instant now = clock.instant();
            long watchlist = syncWatchlist();
            long attempts = replay("""
                    SELECT source_account, transaction_id, (extract(epoch FROM created_at) * 1000)::bigint::text
                    FROM screening WHERE created_at > ?""",
                    now.minus(REALTIME_LOOKBACK),
                    rs -> c -> c.zadd(Keys.out(rs[0]), ZAddArgs.Builder.nx(), millis(rs[2]), rs[1]));
            long debits = replay("""
                    SELECT source_account, transaction_id || '|' || amount::text,
                           (extract(epoch FROM outcome_at) * 1000)::bigint::text FROM screening
                    WHERE outcome = 'COMPLETED' AND outcome_at > ?""",
                    now.minus(REALTIME_LOOKBACK),
                    rs -> c -> c.zadd(Keys.outDone(rs[0]), ZAddArgs.Builder.nx(), millis(rs[2]), rs[1]));
            long payees = replay("""
                    SELECT source_account, payee_account, '' FROM screening
                    WHERE outcome = 'COMPLETED' AND outcome_at > ? GROUP BY source_account, payee_account""",
                    now.minus(payeeLookback),
                    rs -> c -> c.sadd(Keys.payees(rs[0]), rs[1]));
            log.info("Redis rebuild done in {} ms: watchlist={} attempts={} completed={} payees={}",
                    (System.nanoTime() - started) / 1_000_000, watchlist, attempts, debits, payees);
        } catch (RuntimeException e) {
            log.warn("Redis rebuild failed; it runs again when the breaker next closes: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Replaces tg:watchlist with the PostgreSQL table, atomically via RENAME. */
    long syncWatchlist() {
        List<String> accounts = jdbc.queryForList("SELECT account_no FROM watchlist_entry", String.class);
        String tmp = Keys.WATCHLIST + ":sync";
        redis.bulk(c -> {
            if (accounts.isEmpty()) {
                return c.del(Keys.WATCHLIST).toCompletableFuture().thenApply(x -> (Object) x);
            }
            return c.del(tmp).toCompletableFuture()
                    .thenCompose(x -> c.sadd(tmp, accounts.toArray(String[]::new)))
                    .thenCompose(x -> c.rename(tmp, Keys.WATCHLIST))
                    .thenApply(x -> (Object) x);
        });
        return accounts.size();
    }

    private long replay(String sql, Instant since,
                        Function<String[], Function<RedisAsyncCommands<String, String>, RedisFuture<?>>> command) {
        AtomicLong count = new AtomicLong();
        List<Function<RedisAsyncCommands<String, String>, RedisFuture<?>>> batch = new ArrayList<>(BATCH);
        jdbc.query(sql, (RowCallbackHandler) rs -> {
            batch.add(command.apply(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}));
            count.incrementAndGet();
            if (batch.size() >= BATCH) {
                flush(batch);
            }
        }, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        flush(batch);
        return count.get();
    }

    private void flush(List<Function<RedisAsyncCommands<String, String>, RedisFuture<?>>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Function<RedisAsyncCommands<String, String>, RedisFuture<?>>> copy = List.copyOf(batch);
        batch.clear();
        redis.bulk(c -> CompletableFuture.allOf(copy.stream()
                .map(f -> f.apply(c).toCompletableFuture())
                .toArray(CompletableFuture[]::new)));
    }

    private static double millis(String epochMillis) {
        return Double.parseDouble(epochMillis);
    }
}
