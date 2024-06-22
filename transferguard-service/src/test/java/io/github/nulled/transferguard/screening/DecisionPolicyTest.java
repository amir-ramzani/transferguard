package io.github.nulled.transferguard.screening;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.EngineSettings;
import io.github.nulled.transferguard.rules.FailMode;
import io.github.nulled.transferguard.screening.api.Decision;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DecisionPolicyTest {

    private final DecisionPolicy policy = new DecisionPolicy();

    private static EngineSettings settings(FailMode mode) {
        return new EngineSettings(39, 69, mode, "1000.00");
    }

    @ParameterizedTest
    @CsvSource({"0,ALLOW", "39,ALLOW", "40,CHALLENGE", "69,CHALLENGE", "70,HOLD", "200,HOLD"})
    void bands(int score, Decision expected) {
        assertThat(policy.decide(score, false, new BigDecimal("1.00"), settings(FailMode.DEGRADED))).isEqualTo(expected);
    }

    @Test
    void degradedRaisesLargerAmountsToChallenge() {
        assertThat(policy.decide(0, true, new BigDecimal("1000.00"), settings(FailMode.DEGRADED)))
                .isEqualTo(Decision.CHALLENGE);
        assertThat(policy.decide(0, true, new BigDecimal("999.99"), settings(FailMode.DEGRADED)))
                .isEqualTo(Decision.ALLOW);
        assertThat(policy.decide(95, true, new BigDecimal("5000.00"), settings(FailMode.DEGRADED)))
                .isEqualTo(Decision.HOLD);
    }

    @Test
    void failOpenAllows() {
        assertThat(policy.decide(95, true, new BigDecimal("5000.00"), settings(FailMode.OPEN))).isEqualTo(Decision.ALLOW);
    }

    @Test
    void failClosedChallengesEverything() {
        assertThat(policy.decide(0, true, new BigDecimal("1.00"), settings(FailMode.CLOSED)))
                .isEqualTo(Decision.CHALLENGE);
        assertThat(policy.decide(80, true, new BigDecimal("1.00"), settings(FailMode.CLOSED))).isEqualTo(Decision.HOLD);
    }

    @Test
    void failModeOnlyAppliesWhenDegraded() {
        assertThat(policy.decide(0, false, new BigDecimal("5000.00"), settings(FailMode.CLOSED)))
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void missingDegradedMinimumDefaultsToOneThousand() {
        var s = new EngineSettings(39, 69, FailMode.DEGRADED, null);
        assertThat(policy.decide(0, true, new BigDecimal("1000.00"), s)).isEqualTo(Decision.CHALLENGE);
    }
}
