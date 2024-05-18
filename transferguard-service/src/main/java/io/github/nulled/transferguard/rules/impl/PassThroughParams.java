package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;

public record PassThroughParams(
        @NotNull Duration window,
        @NotNull @Min(2) Integer minSenders,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal outRatio)
        implements RuleParams {

    @Override
    public String crossCheck() {
        return Durations.checkWindow("window", window, Durations.MAX_REALTIME_WINDOW);
    }
}
