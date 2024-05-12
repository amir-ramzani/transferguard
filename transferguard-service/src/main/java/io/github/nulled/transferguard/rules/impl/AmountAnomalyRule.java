package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R3: amount at least {@code multiplier} times the 30-day average. Skipped (not fired) when the
 * account has fewer than {@code minHistory} completed transfers, so new accounts never divide by zero.
 */
@Component
public class AmountAnomalyRule implements Rule<AmountAnomalyParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R3_AMOUNT_ANOMALY;
    }

    @Override
    public Class<AmountAnomalyParams> paramsType() {
        return AmountAnomalyParams.class;
    }

    @Override
    public FeatureNeeds needs(AmountAnomalyParams params) {
        return FeatureNeeds.of(Feature.AMOUNT_HISTORY);
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, AmountAnomalyParams params) {
        var history = ctx.features().history();
        if (history.count() < params.minHistory() || history.average().signum() <= 0) {
            ctx.note("rule.R3_AMOUNT_ANOMALY.skipped", "history=" + history.count());
            return Optional.empty();
        }
        BigDecimal amount = ctx.transfer().amount();
        if (amount.compareTo(params.multiplier().multiply(history.average())) >= 0) {
            BigDecimal multiple = amount.divide(history.average(), 0, RoundingMode.HALF_UP);
            return Finding.of("Amount is " + multiple.toPlainString() + "x the 30-day average");
        }
        return Optional.empty();
    }
}
