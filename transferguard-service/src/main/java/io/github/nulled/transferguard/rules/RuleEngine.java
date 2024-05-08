package io.github.nulled.transferguard.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runs every active rule against pre-loaded features, in memory. A rule whose features were not
 * loaded (degraded mode) is skipped, not failed. Reasons are ordered by points, highest first.
 */
@Component
public class RuleEngine {

    public record Evaluation(List<RuleHit> hits, List<RuleCode> skipped, int score) {}

    private static final Comparator<RuleHit> BY_POINTS_THEN_CODE = Comparator
            .comparingInt(RuleHit::points).reversed()
            .thenComparing(RuleHit::code);

    public FeatureNeeds needs(List<ActiveRule> rules) {
        FeatureNeeds merged = FeatureNeeds.NONE;
        for (ActiveRule rule : rules) {
            merged = merged.merge(rule.needs());
        }
        return merged;
    }

    public Evaluation evaluate(List<ActiveRule> rules, ScreeningContext ctx) {
        List<RuleHit> hits = new ArrayList<>();
        List<RuleCode> skipped = new ArrayList<>();
        for (ActiveRule rule : rules) {
            if (!ctx.features().covers(rule.needs())) {
                skipped.add(rule.code());
                continue;
            }
            Optional<Finding> finding = rule.evaluate(ctx);
            finding.ifPresent(f -> hits.add(new RuleHit(rule.code().name(), rule.weight(), f.message())));
        }
        hits.sort(BY_POINTS_THEN_CODE);
        int score = hits.stream().mapToInt(RuleHit::points).sum();
        return new Evaluation(List.copyOf(hits), List.copyOf(skipped), score);
    }
}
