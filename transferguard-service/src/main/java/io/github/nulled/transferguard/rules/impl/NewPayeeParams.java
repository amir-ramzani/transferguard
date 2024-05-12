package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record NewPayeeParams(
        @NotNull @Pattern(regexp = Money.PATTERN, message = "must be a decimal string with two decimal places")
        String minAmount) implements RuleParams {

    public BigDecimal minAmountValue() {
        return Money.parse(minAmount);
    }
}
