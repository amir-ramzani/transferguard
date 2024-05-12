package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Features.AmountHistory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountAnomalyRuleTest {

    private final AmountAnomalyRule rule = new AmountAnomalyRule();
    private final AmountAnomalyParams params = new AmountAnomalyParams(new BigDecimal("5"), 3);

    private static Features history(String avg, long count) {
        return Features.builder().history(new AmountHistory(new BigDecimal(avg), count)).build();
    }

    @Test
    void noHistorySkipsAndLeavesATraceNote() {
        var ctx = ctx("100000.00", Features.builder().history(AmountHistory.NONE).build());
        assertThat(rule.evaluate(ctx, params)).isEmpty();
        assertThat(ctx.notes()).containsKey("rule.R3_AMOUNT_ANOMALY.skipped");
    }

    @Test
    void tooLittleHistorySkips() {
        assertThat(rule.evaluate(ctx("100000.00", history("100.00", 2)), params)).isEmpty();
    }

    @Test
    void exactlyFiveTimesFires() {
        assertThat(rule.evaluate(ctx("500.00", history("100.00", 3)), params)).get().extracting("message")
                .isEqualTo("Amount is 5x the 30-day average");
    }

    @Test
    void justBelowFiveTimesDoesNotFire() {
        assertThat(rule.evaluate(ctx("499.99", history("100.00", 3)), params)).isEmpty();
    }

    @Test
    void messageShowsTheRoundedMultiple() {
        assertThat(rule.evaluate(ctx("4800.00", history("600.00", 10)), params)).get().extracting("message")
                .isEqualTo("Amount is 8x the 30-day average");
        assertThat(rule.evaluate(ctx("575.00", history("100.00", 10)), params)).get().extracting("message")
                .isEqualTo("Amount is 6x the 30-day average");
    }
}
