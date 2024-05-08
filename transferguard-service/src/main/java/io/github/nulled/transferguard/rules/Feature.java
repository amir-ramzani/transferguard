package io.github.nulled.transferguard.rules;

/** A family of pre-loaded data a rule reads. */
public enum Feature {
    /** Attempts from the source account in a window (Redis only). */
    OUTBOUND_VELOCITY,
    /** Inbound credits and completed debits in a window (Redis only). */
    PASS_THROUGH,
    /** Whether the payee has received a completed transfer before. */
    KNOWN_PAYEE,
    /** Device changes and password resets (Redis only). */
    SECURITY,
    /** The 30-day average transfer amount and count. */
    AMOUNT_HISTORY,
    /** Whether the payee is a reported mule account. */
    WATCHLIST
}
