package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R2: a large amount to a payee this account has never completed a transfer to. A payee that was
 * only challenged, never completed, is still new.
 */
@Component
public class NewPayeeHighAmountRule implements Rule<NewPayeeParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R2_NEW_PAYEE_HIGH_AMOUNT;
    }

    @Override
    public Class<NewPayeeParams> paramsType() {
        return NewPayeeParams.class;
    }

    @Override
    public FeatureNeeds needs(NewPayeeParams params) {
        return FeatureNeeds.of(Feature.KNOWN_PAYEE);
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, NewPayeeParams params) {
        var amount = ctx.transfer().amount();
        if (!ctx.features().payeeKnown() && amount.compareTo(params.minAmountValue()) >= 0) {
            return Finding.of("First transfer to this payee is " + Money.display(amount));
        }
        return Optional.empty();
    }
}
