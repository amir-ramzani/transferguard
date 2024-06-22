package io.github.nulled.transferguard.screening.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreeningResponse(UUID screeningId, String transactionId, Decision decision, int score,
                                boolean degraded, List<Reason> reasons, String caseId, OffsetDateTime screenedAt) {}
