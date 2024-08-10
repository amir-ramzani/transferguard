package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.CanonicalJson;
import io.github.nulled.transferguard.common.Sha256;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code hash = hex(SHA-256(prev_hash || JCS({actor, action, entity, before, after, created_at})))}.
 * {@code created_at} is rendered in UTC with exactly six fractional digits, matching PostgreSQL's
 * microsecond precision so the verifier recomputes the same bytes.
 */
public final class AuditHasher {

    public static final String GENESIS = "0".repeat(64);

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditHasher() {}

    public static String canonical(String actor, String action, String entity, JsonNode before, JsonNode after,
                                   Instant createdAt) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode row = f.objectNode();
        row.put("actor", actor);
        row.put("action", action);
        row.put("entity", entity);
        row.set("before", before == null ? f.nullNode() : before);
        row.set("after", after == null ? f.nullNode() : after);
        row.put("created_at", MICROS.format(createdAt));
        return CanonicalJson.write(row);
    }

    public static String hash(String prevHash, String actor, String action, String entity, JsonNode before,
                              JsonNode after, Instant createdAt) {
        return Sha256.hex(prevHash + canonical(actor, action, entity, before, after, createdAt));
    }
}
