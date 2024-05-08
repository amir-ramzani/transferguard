package io.github.nulled.transferguard.rules;

/** Stable reason codes. A bank front end translates these, not the English messages. */
public enum RuleCode {
    R1_VELOCITY,
    R2_NEW_PAYEE_HIGH_AMOUNT,
    R3_AMOUNT_ANOMALY,
    R4_ODD_HOURS,
    R5_PASS_THROUGH,
    R6_NEW_DEVICE,
    R7_WATCHLIST;

    public static RuleCode parse(String value) {
        try {
            return RuleCode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
