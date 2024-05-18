package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** R7: the payee is a reported mule account. */
@Component
public class WatchlistRule implements Rule<WatchlistParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R7_WATCHLIST;
    }

    @Override
    public Class<WatchlistParams> paramsType() {
        return WatchlistParams.class;
    }

    @Override
    public FeatureNeeds needs(WatchlistParams params) {
        return FeatureNeeds.of(Feature.WATCHLIST);
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, WatchlistParams params) {
        if (ctx.features().payeeWatchlisted()) {
            return Finding.of("Payee account is on the mule-account watchlist");
        }
        return Optional.empty();
    }
}
