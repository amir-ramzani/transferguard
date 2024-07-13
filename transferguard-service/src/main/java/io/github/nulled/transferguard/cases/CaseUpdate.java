package io.github.nulled.transferguard.cases;

import jakarta.validation.constraints.Size;

public record CaseUpdate(@Size(max = 128) String assignee, Resolution status,
                         @Size(min = 1, max = 2000) String resolutionNote, Boolean addPayeeToWatchlist) {}
