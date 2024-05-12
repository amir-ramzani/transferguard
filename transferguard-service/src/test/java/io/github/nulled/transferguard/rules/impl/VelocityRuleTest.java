package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.Features;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule();
    private final VelocityParams params = new VelocityParams(5, Duration.ofMinutes(10));

    @Test
    void fifthAttemptDoesNotFire() {
        assertThat(rule.evaluate(ctx("10.00", Features.builder().velocityCount(5).build()), params)).isEmpty();
    }

    @Test
    void sixthAttemptFires() {
        var hit = rule.evaluate(ctx("10.00", Features.builder().velocityCount(6).build()), params);
        assertThat(hit).get().extracting("message").isEqualTo("6 transfers in the last 10 minutes");
    }

    @Test
    void needsTheConfiguredWindow() {
        var needs = rule.needs(new VelocityParams(5, Duration.ofMinutes(15)));
        assertThat(needs.features()).containsExactly(Feature.OUTBOUND_VELOCITY);
        assertThat(needs.velocityWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rejectsZeroAndHugeWindows() {
        assertThat(new VelocityParams(5, Duration.ZERO).crossCheck()).isNotNull();
        assertThat(new VelocityParams(5, Duration.ofHours(25)).crossCheck()).isNotNull();
        assertThat(params.crossCheck()).isNull();
    }

    @Test
    void humanisesHourWindows() {
        var hit = rule.evaluate(ctx("10.00", Features.builder().velocityCount(9).build()),
                new VelocityParams(5, Duration.ofHours(1)));
        assertThat(hit).get().extracting("message").isEqualTo("9 transfers in the last hour");
    }
}
