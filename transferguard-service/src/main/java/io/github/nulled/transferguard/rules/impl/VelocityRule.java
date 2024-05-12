package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R1: more than {@code maxCount} attempts in the window. The count includes this transfer, so with
 * maxCount=5 the sixth attempt fires.
 */
@Component
public class VelocityRule implements Rule<VelocityParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R1_VELOCITY;
    }

    @Override
    public Class<VelocityParams> paramsType() {
        return VelocityParams.class;
    }

    @Override
    public FeatureNeeds needs(VelocityParams params) {
        return FeatureNeeds.of(Feature.OUTBOUND_VELOCITY).withVelocityWindow(params.window());
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, VelocityParams params) {
        int count = ctx.features().velocityCount();
        if (count > params.maxCount()) {
            return Finding.of(count + " transfers in the last " + Durations.human(params.window()));
        }
        return Optional.empty();
    }
}
