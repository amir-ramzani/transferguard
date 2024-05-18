package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import org.junit.jupiter.api.Test;

class WatchlistRuleTest {

    private final WatchlistRule rule = new WatchlistRule();

    @Test
    void listedPayeeFires() {
        assertThat(rule.evaluate(ctx("1.00", Features.builder().payeeWatchlisted(true).build()), new WatchlistParams()))
                .get().extracting("message").isEqualTo("Payee account is on the mule-account watchlist");
    }

    @Test
    void unlistedPayeeDoesNotFire() {
        assertThat(rule.evaluate(ctx("1.00", Features.builder().payeeWatchlisted(false).build()),
                new WatchlistParams())).isEmpty();
    }
}
