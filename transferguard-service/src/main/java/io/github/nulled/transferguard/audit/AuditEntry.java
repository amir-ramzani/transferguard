package io.github.nulled.transferguard.audit;

import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

public record AuditEntry(long id, String actor, String action, String entity, JsonNode before, JsonNode after,
                         String prevHash, String hash, OffsetDateTime createdAt) {}
