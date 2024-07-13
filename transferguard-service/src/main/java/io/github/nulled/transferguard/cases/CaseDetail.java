package io.github.nulled.transferguard.cases;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CaseDetail(String caseId, UUID screeningId, CaseStatus status, String assignee, int score, String amount,
                         OffsetDateTime createdAt, OffsetDateTime resolvedAt, ScreeningDetail screening,
                         List<CaseNote> notes) {

    static CaseDetail of(FraudCase c, ScreeningDetail screening, List<CaseNote> notes) {
        return new CaseDetail(c.caseId(), c.screeningId(), c.status(), c.assignee(), c.score(), c.amount(),
                c.createdAt(), c.resolvedAt(), screening, notes);
    }
}
