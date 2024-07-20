package io.github.nulled.transferguard.watchlist;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WatchlistEntry(String accountNo, WatchlistSource source, String reason, String caseId,
                             OffsetDateTime addedAt, String addedBy) {}
