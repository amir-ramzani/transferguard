package io.github.nulled.transferguard.cases;

import java.time.OffsetDateTime;

public record CaseNote(long noteId, String author, String note, OffsetDateTime createdAt) {}
