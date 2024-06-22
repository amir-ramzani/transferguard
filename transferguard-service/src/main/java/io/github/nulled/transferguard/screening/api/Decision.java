package io.github.nulled.transferguard.screening.api;

/** Ordered by severity, so {@code max} gives the stricter decision. */
public enum Decision {
    ALLOW, CHALLENGE, HOLD;

    public Decision atLeast(Decision floor) {
        return compareTo(floor) >= 0 ? this : floor;
    }
}
