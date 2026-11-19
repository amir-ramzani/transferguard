package io.github.nulled.transferguard.rules;

import io.github.nulled.transferguard.common.Times;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/** The {@code Rule} schema of the API. */
public record RuleView(RuleCode code, String name, boolean enabled, int weight, JsonNode params, int version,
                       OffsetDateTime updatedAt, String updatedBy) {

    static RuleView of(RuleDefinition d) {
        return new RuleView(d.code(), d.name(), d.enabled(), d.weight(), d.rawParams(), d.version(),
                Times.local(d.updatedAt()), d.updatedBy());
    }
}
