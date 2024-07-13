package io.github.nulled.transferguard.cases;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The {@code Case} schema. {@code assignee} and {@code resolvedAt} are always present, possibly null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FraudCase(String caseId, UUID screeningId, CaseStatus status, String assignee, int score, String amount,
                        OffsetDateTime createdAt, OffsetDateTime resolvedAt) {}
