package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AmountAnomalyParams(
        @NotNull @DecimalMin(value = "1", inclusive = false) BigDecimal multiplier,
        @NotNull @Min(1) Integer minHistory) implements RuleParams {}
