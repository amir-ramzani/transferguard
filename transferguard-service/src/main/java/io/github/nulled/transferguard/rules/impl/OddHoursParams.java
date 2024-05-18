package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.rules.RuleParams;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

public record OddHoursParams(
        @NotNull @Pattern(regexp = HH_MM, message = "must be HH:mm") String start,
        @NotNull @Pattern(regexp = HH_MM, message = "must be HH:mm") String end,
        @NotNull @Pattern(regexp = Money.PATTERN, message = "must be a decimal string with two decimal places")
        String minAmount,
        @NotBlank String zone) implements RuleParams {

    static final String HH_MM = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    @Override
    public String crossCheck() {
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            return "zone must be an IANA time zone such as Asia/Kuala_Lumpur";
        }
        if (start.equals(end)) {
            return "start and end must differ";
        }
        return null;
    }

    public LocalTime startTime() {
        return LocalTime.parse(start);
    }

    public LocalTime endTime() {
        return LocalTime.parse(end);
    }

    public BigDecimal minAmountValue() {
        return Money.parse(minAmount);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
