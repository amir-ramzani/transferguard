package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Features.InboundCredit;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R5 (mule pattern): within one window the account received credits from at least
 * {@code minSenders} distinct senders, and completed outbound transfers plus this one reach
 * {@code outRatio} of the inbound total.
 */
@Component
public class PassThroughRule implements Rule<PassThroughParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R5_PASS_THROUGH;
    }

    @Override
    public Class<PassThroughParams> paramsType() {
        return PassThroughParams.class;
    }

    @Override
    public FeatureNeeds needs(PassThroughParams params) {
        return FeatureNeeds.of(Feature.PASS_THROUGH).withPassThroughWindow(params.window());
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, PassThroughParams params) {
        var credits = ctx.features().inboundCredits();
        if (credits.isEmpty()) {
            return Optional.empty();
        }
        long senders = credits.stream().map(InboundCredit::sender).distinct().count();
        if (senders < params.minSenders()) {
            return Optional.empty();
        }
        BigDecimal inbound = credits.stream().map(InboundCredit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inbound.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal outbound = ctx.features().completedOutbound().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(ctx.transfer().amount());
        if (outbound.compareTo(params.outRatio().multiply(inbound)) >= 0) {
            BigDecimal percent = outbound.multiply(BigDecimal.valueOf(100)).divide(inbound, 0, RoundingMode.HALF_UP);
            return Finding.of(Money.display(outbound) + " (" + percent.toPlainString() + "%) of "
                    + Money.display(inbound) + " received from " + senders + " senders in the last "
                    + Durations.human(params.window()) + " is being sent on");
        }
        return Optional.empty();
    }
}
