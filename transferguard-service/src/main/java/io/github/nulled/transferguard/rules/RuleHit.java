package io.github.nulled.transferguard.rules;

/** One reason on a screening: a rule code (or DEGRADED_SCREENING), its points and a message. */
public record RuleHit(String code, int points, String message) {

    public static final String DEGRADED_SCREENING = "DEGRADED_SCREENING";
}
