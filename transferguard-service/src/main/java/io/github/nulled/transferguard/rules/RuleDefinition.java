package io.github.nulled.transferguard.rules;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;

/** A rule row: configuration as stored, plus its parsed parameter record. */
public record RuleDefinition(RuleCode code, String name, boolean enabled, int weight, JsonNode rawParams,
                             RuleParams params, int version, Instant updatedAt, String updatedBy) {}
