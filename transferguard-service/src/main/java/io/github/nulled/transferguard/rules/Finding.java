package io.github.nulled.transferguard.rules;

import java.util.Optional;

/** A rule fired, with a human-readable message that has the numbers filled in. */
public record Finding(String message) {

    public static Optional<Finding> of(String message) {
        return Optional.of(new Finding(message));
    }
}
