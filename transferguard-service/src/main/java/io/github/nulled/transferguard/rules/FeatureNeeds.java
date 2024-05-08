package io.github.nulled.transferguard.rules;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the enabled rules need loaded, merged across rules so the features are read in one Redis
 * round trip. Window lengths come from the live rule parameters.
 */
public record FeatureNeeds(Set<Feature> features, Duration velocityWindow, Duration passThroughWindow,
                           Duration securityWindow) {

    public static final FeatureNeeds NONE = new FeatureNeeds(Set.of(), null, null, null);

    public FeatureNeeds {
        features = features.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(features));
    }

    public static FeatureNeeds of(Feature... features) {
        return new FeatureNeeds(features.length == 0 ? Set.of() : EnumSet.of(features[0], features), null, null, null);
    }

    public FeatureNeeds withVelocityWindow(Duration window) {
        return new FeatureNeeds(features, window, passThroughWindow, securityWindow);
    }

    public FeatureNeeds withPassThroughWindow(Duration window) {
        return new FeatureNeeds(features, velocityWindow, window, securityWindow);
    }

    public FeatureNeeds withSecurityWindow(Duration window) {
        return new FeatureNeeds(features, velocityWindow, passThroughWindow, window);
    }

    public boolean needs(Feature feature) {
        return features.contains(feature);
    }

    public FeatureNeeds merge(FeatureNeeds other) {
        EnumSet<Feature> all = EnumSet.noneOf(Feature.class);
        all.addAll(features);
        all.addAll(other.features);
        return new FeatureNeeds(all, max(velocityWindow, other.velocityWindow),
                max(passThroughWindow, other.passThroughWindow), max(securityWindow, other.securityWindow));
    }

    private static Duration max(Duration a, Duration b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }
}
