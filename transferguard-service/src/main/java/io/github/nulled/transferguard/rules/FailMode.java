package io.github.nulled.transferguard.rules;

/** Behaviour when Redis is unavailable (ADR 003). */
public enum FailMode {
    /** Allow everything, unscreened. Lowest friction, highest fraud loss. */
    OPEN,
    /** Run the rules PostgreSQL can answer and raise the floor for larger amounts. */
    DEGRADED,
    /** Run the rules PostgreSQL can answer and challenge every transfer. */
    CLOSED
}
