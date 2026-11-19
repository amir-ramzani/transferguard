package io.github.nulled.transferguard.rules;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record RuleUpdateRequest(@NotNull Boolean enabled, @NotNull @Min(0) @Max(100) Integer weight,
                                @NotNull JsonNode params) {}
