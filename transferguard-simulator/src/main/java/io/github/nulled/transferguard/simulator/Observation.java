package io.github.nulled.transferguard.simulator;

import java.util.List;

/** One screened transfer with its ground-truth label. */
public record Observation(Scenario scenario, boolean fraud, String screeningId, String decision, int score,
                          List<String> reasons, long latencyMs, int status) {}
