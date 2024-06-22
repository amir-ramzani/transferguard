package io.github.nulled.transferguard.screening.api;

import io.github.nulled.transferguard.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record ScreeningRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "may contain only letters, digits and '-'")
        String transactionId,
        @NotNull @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String sourceAccount,
        @NotNull @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String payeeAccount,
        @NotNull @Pattern(regexp = Money.PATTERN, message = "must be a decimal string with two decimal places")
        String amount,
        @NotNull Currency currency,
        @NotNull Channel channel,
        @Size(max = 128) String deviceId,
        @NotNull OffsetDateTime requestedAt) {}
