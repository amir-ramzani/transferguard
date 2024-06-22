package io.github.nulled.transferguard.screening.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code ScreeningResponse} plus the original request and what happened to the transfer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreeningDetail(UUID screeningId, String transactionId, Decision decision, int score, boolean degraded,
                              List<Reason> reasons, String caseId, OffsetDateTime screenedAt,
                              ScreeningRequest request, TransferOutcome outcome, OffsetDateTime outcomeAt) {

    public ScreeningResponse toResponse() {
        return new ScreeningResponse(screeningId, transactionId, decision, score, degraded, reasons, caseId, screenedAt);
    }
}
