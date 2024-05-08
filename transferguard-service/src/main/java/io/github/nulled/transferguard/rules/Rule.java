package io.github.nulled.transferguard.rules;

import java.util.Optional;

/**
 * A detection rule. Each rule is a Spring bean that reads pre-loaded {@link Features} and does no
 * I/O of its own, so a rule unit test needs no mocks. Points come from the rule's configured weight.
 */
public interface Rule<P extends RuleParams> {

    RuleCode code();

    /** The parameter record, e.g. {@code VelocityParams}. */
    Class<P> paramsType();

    /** Windows and feature families this rule reads, for the given parameters. */
    FeatureNeeds needs(P params);

    Optional<Finding> evaluate(ScreeningContext ctx, P params);
}
