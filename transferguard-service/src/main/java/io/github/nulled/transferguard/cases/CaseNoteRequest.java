package io.github.nulled.transferguard.cases;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CaseNoteRequest(@NotBlank @Size(max = 2000) String note) {}
