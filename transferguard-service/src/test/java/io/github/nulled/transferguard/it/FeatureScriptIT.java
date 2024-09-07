package io.github.nulled.transferguard.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.features.RedisFeatureLoader;
import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Transfer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** screen_features.lua against a real Redis. */
class FeatureScriptIT extends IntegrationTest {

    @Autowired
    RedisFeatureLoader loader;

    private final FeatureNeeds velocity = FeatureNeeds.of(Feature.OUTBOUND_VELOCITY).withVelocityWindow(Duration.ofMinutes(10));

    private Transfer t(String source, String txn, Instant at) {
        return new Transfer(txn, source, "1402-0098-7654", new BigDecimal("10.00"), null, at);
    }

    @Test
    void countsIncludeThisAttemptAndReplaysDoNotDoubleCount() {
        String source = account();
        Instant t0 = Instant.parse("2026-11-19T04:00:00Z");
        assertThat(loader.load(t(source, "A", t0), velocity, t0).velocityCount()).isEqualTo(1);
        assertThat(loader.load(t(source, "A", t0), velocity, t0.plusSeconds(1)).velocityCount()).isEqualTo(1);
        assertThat(loader.load(t(source, "B", t0), velocity, t0.plusSeconds(2)).velocityCount()).isEqualTo(2);
    }

    @Test
    void anAttemptExactlyOneWindowOldHasLeft() {
        String source = account();
        Instant t0 = Instant.parse("2026-11-19T04:00:00Z");
        loader.load(t(source, "A", t0), velocity, t0);
        Instant justInside = t0.plus(Duration.ofMinutes(10)).minusMillis(1);
        assertThat(loader.load(t(source, "B", justInside), velocity, justInside).velocityCount()).isEqualTo(2);
        Instant edge = t0.plus(Duration.ofMinutes(10));
        assertThat(loader.load(t(source, "C", edge), velocity, edge).velocityCount()).isEqualTo(2);
    }

    @Test
    void returnsOnlyWhatWasAskedFor() {
        var f = loader.load(t(account(), "X", Instant.now()), FeatureNeeds.of(Feature.KNOWN_PAYEE), Instant.now());
        assertThat(f.available()).containsExactly(Feature.KNOWN_PAYEE);
        assertThat(f.payeeKnown()).isFalse();
    }
}
