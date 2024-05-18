package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

public record NewDeviceParams(@NotNull Duration window) implements RuleParams {

    /** Security state is kept for 30 days in Redis. */
    static final Duration MAX_WINDOW = Duration.ofDays(30);

    @Override
    public String crossCheck() {
        return Durations.checkWindow("window", window, MAX_WINDOW);
    }
}
