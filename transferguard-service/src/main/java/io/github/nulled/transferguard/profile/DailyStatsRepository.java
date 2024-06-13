package io.github.nulled.transferguard.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DailyStatsRepository {

    public record Totals(BigDecimal amountSum, long txnCount) {}

    private final JdbcClient jdbc;

    public DailyStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String account, LocalDate day, BigDecimal amount) {
        jdbc.sql("""
                INSERT INTO account_daily_stats (account_no, day, amount_sum, txn_count) VALUES (?, ?, ?, 1)
                ON CONFLICT (account_no, day) DO UPDATE
                SET amount_sum = account_daily_stats.amount_sum + EXCLUDED.amount_sum,
                    txn_count  = account_daily_stats.txn_count + 1""")
                .params(account, day, amount).update();
    }

    /** Sums at most 30 rows through the primary key. */
    public Totals totals(String account, LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.sql("""
                SELECT coalesce(sum(amount_sum), 0) AS s, coalesce(sum(txn_count), 0) AS c
                FROM account_daily_stats WHERE account_no = ? AND day BETWEEN ? AND ?""")
                .params(account, fromInclusive, toInclusive)
                .query((rs, n) -> new Totals(rs.getBigDecimal("s"), rs.getLong("c")))
                .single();
    }

    public int purgeBefore(LocalDate day) {
        return jdbc.sql("DELETE FROM account_daily_stats WHERE day < ?").param(day).update();
    }
}
