package io.github.nulled.transferguard.rules;

import java.math.BigDecimal;
import java.time.Instant;

/** The transfer being screened, as the rules see it. */
public record Transfer(String transactionId, String sourceAccount, String payeeAccount, BigDecimal amount,
                       String deviceId, Instant requestedAt) {}
