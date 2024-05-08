package io.github.nulled.transferguard.rules;

import java.util.Optional;

/** An enabled rule bound to its current configuration. */
public record ActiveRule(Rule<?> rule, RuleDefinition definition) {

    public RuleCode code() {
        return rule.code();
    }

    public int weight() {
        return definition.weight();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FeatureNeeds needs() {
        return ((Rule) rule).needs(definition.params());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<Finding> evaluate(ScreeningContext ctx) {
        return ((Rule) rule).evaluate(ctx, definition.params());
    }
}
