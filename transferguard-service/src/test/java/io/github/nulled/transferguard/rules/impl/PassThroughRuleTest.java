package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Features.InboundCredit;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassThroughRuleTest {

    private final PassThroughRule rule = new PassThroughRule();
    private final PassThroughParams params = new PassThroughParams(Duration.ofMinutes(60), 3, new BigDecimal("0.80"));

    private static InboundCredit credit(String sender, String amount) {
        return new InboundCredit("C-" + sender + amount, sender, new BigDecimal(amount));
    }

    private static Features features(List<InboundCredit> credits, List<String> debits) {
        return Features.builder().passThrough(credits, debits.stream().map(BigDecimal::new).toList()).build();
    }

    private final List<InboundCredit> threeSenders = List.of(credit("A", "1000.00"), credit("B", "1000.00"),
            credit("C", "1000.00"));

    @Test
    void creditsFromOneSenderDoNotFire() {
        var f = features(List.of(credit("A", "1000.00"), credit("A", "2000.00"), credit("A", "3000.00")), List.of());
        assertThat(rule.evaluate(ctx("6000.00", f), params)).isEmpty();
    }

    @Test
    void noInboundSkipsWithoutDividingByZero() {
        assertThat(rule.evaluate(ctx("6000.00", features(List.of(), List.of())), params)).isEmpty();
    }

    @Test
    void eightyPercentOutFires() {
        assertThat(rule.evaluate(ctx("2400.00", features(threeSenders, List.of())), params)).get()
                .extracting("message")
                .isEqualTo("RM 2,400 (80%) of RM 3,000 received from 3 senders in the last hour is being sent on");
    }

    @Test
    void justBelowEightyPercentDoesNotFire() {
        assertThat(rule.evaluate(ctx("2399.99", features(threeSenders, List.of())), params)).isEmpty();
    }

    @Test
    void completedOutboundCountsTowardsTheRatio() {
        assertThat(rule.evaluate(ctx("400.00", features(threeSenders, List.of("2000.00"))), params)).isPresent();
        assertThat(rule.evaluate(ctx("399.99", features(threeSenders, List.of("2000.00"))), params)).isEmpty();
    }

    @Test
    void twoSendersAreNotEnough() {
        var f = features(List.of(credit("A", "5000.00"), credit("B", "5000.00")), List.of());
        assertThat(rule.evaluate(ctx("9000.00", f), params)).isEmpty();
    }
}
