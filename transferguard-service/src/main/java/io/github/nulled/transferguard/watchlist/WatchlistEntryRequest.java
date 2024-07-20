package io.github.nulled.transferguard.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WatchlistEntryRequest(
        @NotNull WatchlistSource source,
        @NotBlank @Size(max = 500) String reason,
        @Pattern(regexp = "^CASE-[0-9]{6,}$", message = "must look like CASE-000041") String caseId) {}
