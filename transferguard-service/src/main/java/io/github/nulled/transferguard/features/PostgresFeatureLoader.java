package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.profile.AccountProfileService;
import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Transfer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Degraded mode: the features PostgreSQL can still answer. Known payee (R2) from completed
 * screenings, history (R3) from account_daily_stats and the watchlist (R7). R1, R5 and R6 need
 * Redis windows and are skipped.
 */
@Component
public class PostgresFeatureLoader {

    static final Duration PAYEE_MEMORY = Duration.ofDays(400);

    private final JdbcClient jdbc;
    private final AccountProfileService profile;

    public PostgresFeatureLoader(JdbcClient jdbc, AccountProfileService profile) {
        this.jdbc = jdbc;
        this.profile = profile;
    }

    public Features load(Transfer t, FeatureNeeds needs, Instant now) {
        Features.Builder b = Features.builder();
        if (needs.needs(Feature.KNOWN_PAYEE)) {
            boolean known = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM screening
                                   WHERE source_account = ? AND payee_account = ? AND outcome = 'COMPLETED'
                                     AND outcome_at > ?)""")
                    .params(t.sourceAccount(), t.payeeAccount(),
                            OffsetDateTime.ofInstant(now.minus(PAYEE_MEMORY), ZoneOffset.UTC))
                    .query(Boolean.class).single();
            b.payeeKnown(known);
        }
        if (needs.needs(Feature.AMOUNT_HISTORY)) {
            b.history(profile.history(t.sourceAccount(), now));
        }
        if (needs.needs(Feature.WATCHLIST)) {
            boolean listed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM watchlist_entry WHERE account_no = ?)")
                    .param(t.payeeAccount()).query(Boolean.class).single();
            b.payeeWatchlisted(listed);
        }
        return b.build();
    }
}
