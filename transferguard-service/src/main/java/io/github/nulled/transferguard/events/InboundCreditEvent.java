package io.github.nulled.transferguard.events;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.screening.api.Accounts;
import io.github.nulled.transferguard.screening.api.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record InboundCreditEvent(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[^|]+$", message = "must not contain '|'") String creditId,
        @NotNull @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String account,
        @NotBlank @Size(max = 34) @Pattern(regexp = "^[^|]+$", message = "must not contain '|'") String senderAccount,
        @NotNull @Pattern(regexp = Money.PATTERN, message = "must be a decimal string with two decimal places")
        String amount,
        @NotNull Currency currency,
        @NotNull OffsetDateTime receivedAt) {}
