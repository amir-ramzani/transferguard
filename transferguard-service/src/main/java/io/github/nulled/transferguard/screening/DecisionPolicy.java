package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.rules.EngineSettings;
import io.github.nulled.transferguard.screening.api.Decision;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Maps a score to a decision using the bands in {@code engine_setting}, then applies the fail mode
 * when the screening was degraded (ADR 003).
 */
@Component
public class DecisionPolicy {

    public Decision bands(int score, EngineSettings s) {
        if (score <= s.allowMax()) {
            return Decision.ALLOW;
        }
        if (score <= s.challengeMax()) {
            return Decision.CHALLENGE;
        }
        return Decision.HOLD;
    }

    public Decision decide(int score, boolean degraded, BigDecimal amount, EngineSettings s) {
        Decision decision = bands(score, s);
        if (!degraded) {
            return decision;
        }
        return switch (s.failMode()) {
            case OPEN -> Decision.ALLOW;
            case DEGRADED -> amount.compareTo(s.degradedChallengeMin()) >= 0
                    ? decision.atLeast(Decision.CHALLENGE) : decision;
            case CLOSED -> decision.atLeast(Decision.CHALLENGE);
        };
    }
}
