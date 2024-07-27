package io.github.nulled.transferguard.events;

import io.github.nulled.transferguard.screening.api.Accounts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record SecurityEvent(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String account,
        @NotNull SecurityEventType type,
        @Size(max = 128) String deviceId,
        @NotNull OffsetDateTime occurredAt) {}
