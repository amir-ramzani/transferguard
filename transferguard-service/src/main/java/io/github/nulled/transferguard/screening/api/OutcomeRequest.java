package io.github.nulled.transferguard.screening.api;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record OutcomeRequest(@NotNull TransferOutcome outcome, @NotNull OffsetDateTime occurredAt) {}
