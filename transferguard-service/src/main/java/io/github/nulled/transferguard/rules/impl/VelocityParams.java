package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

public record VelocityParams(@NotNull @Min(1) Integer maxCount, @NotNull Duration window) implements RuleParams {

    @Override
    public String crossCheck() {
        return Durations.checkWindow("window", window, Durations.MAX_REALTIME_WINDOW);
    }
}
