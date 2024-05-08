package io.github.nulled.transferguard.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the rules read, loaded before evaluation. {@code available} lists the feature families
 * that were actually loaded: in degraded mode the Redis-only ones are missing and their rules skip.
 */
public record Features(
        Set<Feature> available,
        int velocityCount,
        List<InboundCredit> inboundCredits,
        List<BigDecimal> completedOutbound,
        boolean payeeKnown,
        SecurityState security,
        AmountHistory history,
        boolean payeeWatchlisted) {

    public record InboundCredit(String creditId, String sender, BigDecimal amount) {}

    /** Device and credential state from security events. All fields may be null. */
    public record SecurityState(String deviceId, Instant deviceChangedAt, Instant passwordResetAt) {
        public static final SecurityState NONE = new SecurityState(null, null, null);
    }

    /** Completed transfers over the last 30 days. */
    public record AmountHistory(BigDecimal average, long count) {
        public static final AmountHistory NONE = new AmountHistory(BigDecimal.ZERO, 0);
    }

    public Features {
        available = available.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(available));
        inboundCredits = inboundCredits == null ? List.of() : List.copyOf(inboundCredits);
        completedOutbound = completedOutbound == null ? List.of() : List.copyOf(completedOutbound);
        security = security == null ? SecurityState.NONE : security;
        history = history == null ? AmountHistory.NONE : history;
    }

    public boolean covers(FeatureNeeds needs) {
        return available.containsAll(needs.features());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Set<Feature> available = EnumSet.noneOf(Feature.class);
        private int velocityCount;
        private List<InboundCredit> inboundCredits = List.of();
        private List<BigDecimal> completedOutbound = List.of();
        private boolean payeeKnown;
        private SecurityState security = SecurityState.NONE;
        private AmountHistory history = AmountHistory.NONE;
        private boolean payeeWatchlisted;

        public Builder velocityCount(int count) {
            available.add(Feature.OUTBOUND_VELOCITY);
            this.velocityCount = count;
            return this;
        }

        public Builder passThrough(List<InboundCredit> credits, List<BigDecimal> debits) {
            available.add(Feature.PASS_THROUGH);
            this.inboundCredits = credits;
            this.completedOutbound = debits;
            return this;
        }

        public Builder payeeKnown(boolean known) {
            available.add(Feature.KNOWN_PAYEE);
            this.payeeKnown = known;
            return this;
        }

        public Builder security(SecurityState state) {
            available.add(Feature.SECURITY);
            this.security = state;
            return this;
        }

        public Builder history(AmountHistory history) {
            available.add(Feature.AMOUNT_HISTORY);
            this.history = history;
            return this;
        }

        public Builder payeeWatchlisted(boolean listed) {
            available.add(Feature.WATCHLIST);
            this.payeeWatchlisted = listed;
            return this;
        }

        public Features build() {
            return new Features(available, velocityCount, inboundCredits, completedOutbound, payeeKnown, security,
                    history, payeeWatchlisted);
        }
    }
}
