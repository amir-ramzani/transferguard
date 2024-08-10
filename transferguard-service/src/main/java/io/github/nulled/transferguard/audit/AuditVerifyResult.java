package io.github.nulled.transferguard.audit;

import java.time.OffsetDateTime;

public record AuditVerifyResult(boolean valid, long entriesChecked, Long firstBrokenId, String headHash,
                                OffsetDateTime checkedAt) {}
