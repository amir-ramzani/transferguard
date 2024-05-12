package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import org.junit.jupiter.api.Test;

class NewPayeeHighAmountRuleTest {

    private final NewPayeeHighAmountRule rule = new NewPayeeHighAmountRule();
    private final NewPayeeParams params = new NewPayeeParams("3000.00");
    private final Features newPayee = Features.builder().payeeKnown(false).build();

    @Test
    void justBelowThresholdDoesNotFire() {
        assertThat(rule.evaluate(ctx("2999.99", newPayee), params)).isEmpty();
    }

    @Test
    void thresholdFires() {
        assertThat(rule.evaluate(ctx("3000.00", newPayee), params)).get().extracting("message")
                .isEqualTo("First transfer to this payee is RM 3,000");
    }

    @Test
    void messageShowsCentsOnlyWhenPresent() {
        assertThat(rule.evaluate(ctx("4800.50", newPayee), params)).get().extracting("message")
                .isEqualTo("First transfer to this payee is RM 4,800.50");
    }

    @Test
    void knownPayeeDoesNotFire() {
        assertThat(rule.evaluate(ctx("9000.00", Features.builder().payeeKnown(true).build()), params)).isEmpty();
    }
}
