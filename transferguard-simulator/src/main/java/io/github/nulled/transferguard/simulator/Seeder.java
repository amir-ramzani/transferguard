package io.github.nulled.transferguard.simulator;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Seeds 30 days of completed history so the rules have something to compare against: daily stats
 * (R3), completed screenings plus the Redis known-payee sets (R2) and registered devices (R6).
 * History cannot go through the API, which only accepts requests timed within 5 minutes of now,
 * so it is written straight to the stores. Idempotent: run it as often as you like.
 */
public final class Seeder {

    private static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");
    private static final int DAYS = 30;

    private final SimConfig config;

    public Seeder(SimConfig config) {
        this.config = config;
    }

    public void seed() throws Exception {
        Random random = new Random(config.seed());
        Instant now = Instant.now();
        RedisClient redisClient = RedisClient.create(config.redisUrl());
        try (Connection db = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
             var redisConnection = redisClient.connect()) {
            db.setAutoCommit(false);
            RedisCommands<String, String> redis = redisConnection.sync();
            int rows = 0;
            for (int i = 1; i <= config.normalAccounts(); i++) {
                rows += seedAccount(db, redis, Accounts.customer(i), i, random, now, 1.5, 300);
                if (i % 100 == 0) {
                    db.commit();
                    System.out.printf("seeded %d/%d customers%n", i, config.normalAccounts());
                }
            }
            rows += seedAccount(db, redis, Accounts.TAKEOVER_VICTIM, 0, random, now, 0.5, 600);
            db.commit();
            System.out.printf("Seeded %d completed transfers over %d days for %d customers and the takeover victim%n",
                    rows, DAYS, config.normalAccounts());
        } finally {
            redisClient.shutdown();
        }
    }

    private int seedAccount(Connection db, RedisCommands<String, String> redis, String account, int index,
                            Random random, Instant now, double perDay, int typicalAmount) throws Exception {
        Map<LocalDate, BigDecimal[]> daily = new HashMap<>();
        int n = 0;
        try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO screening (id, transaction_id, request_hash, source_account, payee_account, amount, currency,
                                       channel, device_id, score, decision, degraded, outcome, outcome_at,
                                       requested_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'MYR', 'MOBILE', ?, 0, 'ALLOW', false, 'COMPLETED', ?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING""")) {
            for (int day = DAYS; day >= 1; day--) {
                int count = (int) Math.floor(perDay + random.nextDouble());
                for (int k = 0; k < count; k++) {
                    Instant at = now.minus(Duration.ofDays(day)).plus(Duration.ofMinutes(random.nextInt(600)));
                    BigDecimal amount = BigDecimal.valueOf(typicalAmount * (0.4 + random.nextDouble() * 1.2))
                            .setScale(2, RoundingMode.HALF_UP);
                    String payee = index > 0 ? Accounts.payee(index, random.nextInt(Accounts.PAYEES_PER_ACCOUNT))
                            : "1402-6000-%06d".formatted(random.nextInt(3));
                    Timestamp ts = Timestamp.from(at);
                    insert.setObject(1, UUID.nameUUIDFromBytes((account + day + "-" + k).getBytes()));
                    insert.setString(2, "SEED-" + account + "-" + day + "-" + k);
                    insert.setString(3, "0".repeat(64));
                    insert.setString(4, account);
                    insert.setString(5, payee);
                    insert.setBigDecimal(6, amount);
                    insert.setString(7, Accounts.device(account));
                    insert.setTimestamp(8, ts);
                    insert.setTimestamp(9, ts);
                    insert.setTimestamp(10, ts);
                    insert.addBatch();
                    daily.computeIfAbsent(at.atZone(KL).toLocalDate(), d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    BigDecimal[] t = daily.get(at.atZone(KL).toLocalDate());
                    t[0] = t[0].add(amount);
                    t[1] = t[1].add(BigDecimal.ONE);
                    redis.sadd("tg:{" + account + "}:payees", payee);
                    n++;
                }
            }
            insert.executeBatch();
        }
        try (PreparedStatement stats = db.prepareStatement("""
                INSERT INTO account_daily_stats (account_no, day, amount_sum, txn_count) VALUES (?, ?, ?, ?)
                ON CONFLICT (account_no, day) DO UPDATE SET amount_sum = EXCLUDED.amount_sum, txn_count = EXCLUDED.txn_count""")) {
            for (var e : daily.entrySet()) {
                stats.setString(1, account);
                stats.setObject(2, e.getKey());
                stats.setBigDecimal(3, e.getValue()[0]);
                stats.setInt(4, e.getValue()[1].intValue());
                stats.addBatch();
            }
            stats.executeBatch();
        }
        String payees = "tg:{" + account + "}:payees";
        redis.pexpire(payees, Duration.ofDays(400).toMillis());
        String sec = "tg:{" + account + "}:sec";
        redis.hset(sec, Map.of("deviceId", Accounts.device(account),
                "deviceChangedAt", Long.toString(now.minus(Duration.ofDays(29)).toEpochMilli())));
        redis.hdel(sec, "pwdResetAt");
        redis.pexpire(sec, Duration.ofDays(30).toMillis());
        redis.del("tg:{" + account + "}:avg");
        return n;
    }
}
