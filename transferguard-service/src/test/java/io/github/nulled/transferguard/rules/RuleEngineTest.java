package io.github.nulled.transferguard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features.AmountHistory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    @Test
    void mergesNeedsAcrossRules() {
        FeatureNeeds needs = engine.needs(SeedRules.active());
        assertThat(needs.features()).containsExactlyInAnyOrder(Feature.values());
        assertThat(needs.velocityWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(needs.passThroughWindow()).isEqualTo(Duration.ofMinutes(60));
        assertThat(needs.securityWindow()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void skipsRulesWhoseFeaturesWereNotLoaded() {
        // Degraded mode: only PostgreSQL-backed features.
        Features degraded = Features.builder().payeeKnown(false).history(AmountHistory.NONE)
                .payeeWatchlisted(true).build();
        Transfer t = new Transfer("T", "1402-0012-3456", "1402-0098-7654", new BigDecimal("5000.00"), null,
                Instant.parse("2026-11-19T04:00:00Z"));
        var eval = engine.evaluate(SeedRules.active(), new ScreeningContext(t, degraded, t.requestedAt()));
        assertThat(eval.skipped()).containsExactly(RuleCode.R1_VELOCITY, RuleCode.R5_PASS_THROUGH,
                RuleCode.R6_NEW_DEVICE);
        assertThat(eval.hits()).extracting(RuleHit::code)
                .containsExactly("R7_WATCHLIST", "R2_NEW_PAYEE_HIGH_AMOUNT");
        assertThat(eval.score()).isEqualTo(95);
    }

    @Test
    void ordersReasonsByPointsThenCode() {
        Features f = Features.builder().velocityCount(9).payeeKnown(false)
                .history(new AmountHistory(new BigDecimal("100.00"), 10)).payeeWatchlisted(false)
                .security(Features.SecurityState.NONE).passThrough(java.util.List.of(), java.util.List.of()).build();
        Transfer t = new Transfer("T", "1402-0012-3456", "1402-0098-7654", new BigDecimal("5000.00"), null,
                Instant.parse("2026-11-19T04:00:00Z"));
        var eval = engine.evaluate(SeedRules.active(), new ScreeningContext(t, f, t.requestedAt()));
        assertThat(eval.hits()).extracting(RuleHit::code)
                .containsExactly("R1_VELOCITY", "R2_NEW_PAYEE_HIGH_AMOUNT", "R3_AMOUNT_ANOMALY");
        assertThat(eval.score()).isEqualTo(40 + 25 + 25);
    }
}
