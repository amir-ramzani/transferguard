package io.github.nulled.transferguard.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends one hash-chained row in the caller's transaction, so an administrative change and its
 * audit row commit or roll back together.
 */
@Component
public class AuditWriter {

    private final AuditRepository repository;
    private final JsonMapper mapper;
    private final Clock clock;

    public AuditWriter(AuditRepository repository, JsonMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long append(String actor, String action, String entity, Object before, Object after) {
        repository.lockChain();
        String prevHash = repository.latestHash().orElse(AuditHasher.GENESIS);
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        JsonNode b = toNode(before);
        JsonNode a = toNode(after);
        String hash = AuditHasher.hash(prevHash, actor, action, entity, b, a, createdAt);
        return repository.insert(actor, action, entity, b, a, prevHash, hash, createdAt);
    }

    private JsonNode toNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        // Round-trip through text so the hashed tree has the same shape as the stored jsonb.
        return mapper.readTree(mapper.writeValueAsString(value));
    }
}
