package io.github.nulled.transferguard.simulator;

import java.util.Set;

/** The five traffic patterns, and which rules are meant to catch each fraud pattern. */
public enum Scenario {
    NORMAL(false, Set.of()),
    ACCOUNT_TAKEOVER(true, Set.of("R2_NEW_PAYEE_HIGH_AMOUNT", "R3_AMOUNT_ANOMALY", "R4_ODD_HOURS", "R6_NEW_DEVICE")),
    MULE_RING(true, Set.of("R5_PASS_THROUGH", "R2_NEW_PAYEE_HIGH_AMOUNT")),
    VELOCITY_BURST(true, Set.of("R1_VELOCITY")),
    KNOWN_MULE(true, Set.of("R7_WATCHLIST"));

    private final boolean fraud;
    private final Set<String> targetRules;

    Scenario(boolean fraud, Set<String> targetRules) {
        this.fraud = fraud;
        this.targetRules = targetRules;
    }

    public boolean fraud() {
        return fraud;
    }

    public Set<String> targetRules() {
        return targetRules;
    }

    public String expectedFinalDecision() {
        return switch (this) {
            case NORMAL -> "ALLOW";
            case VELOCITY_BURST -> "CHALLENGE";
            default -> "HOLD";
        };
    }
}
