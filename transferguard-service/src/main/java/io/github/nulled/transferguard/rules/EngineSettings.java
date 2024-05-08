package io.github.nulled.transferguard.rules;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nulled.transferguard.common.Money;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Decision bands: scores up to allowMax ALLOW, up to challengeMax CHALLENGE, above that HOLD. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineSettings(
        @NotNull @Min(0) Integer allowMax,
        @NotNull @Min(1) Integer challengeMax,
        @NotNull FailMode failMode,
        @Pattern(regexp = Money.PATTERN, message = "must be a decimal string with two decimal places")
        String degradedChallengeMinAmount) {

    public static final String DEFAULT_DEGRADED_MIN = "1000.00";

    public BigDecimal degradedChallengeMin() {
        return Money.parse(degradedChallengeMinAmount == null ? DEFAULT_DEGRADED_MIN : degradedChallengeMinAmount);
    }
}
