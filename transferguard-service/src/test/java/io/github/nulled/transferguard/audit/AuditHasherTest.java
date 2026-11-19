package io.github.nulled.transferguard.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditHasherTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonNode before = mapper.readTree("{\"weight\":40,\"params\":{\"window\":\"PT10M\",\"maxCount\":5}}");
    private final JsonNode after = mapper.readTree("{\"params\":{\"maxCount\":5,\"window\":\"PT15M\"},\"weight\":30}");
    private final Instant at = Instant.parse("2026-11-19T01:42:10.184Z");

    /** Vector computed independently (Python hashlib + json.dumps(sort_keys=True)) as sha256("0"*64 + canonical). */
    @Test
    void matchesTheIndependentTestVector() {
        assertThat(AuditHasher.canonical("analyst-1", "RULE_UPDATED", "rule:R1_VELOCITY", before, after, at))
                .isEqualTo("{\"action\":\"RULE_UPDATED\",\"actor\":\"analyst-1\",\"after\":{\"params\":{\"maxCount\":5,"
                        + "\"window\":\"PT15M\"},\"weight\":30},\"before\":{\"params\":{\"maxCount\":5,\"window\":\"PT10M\"},"
                        + "\"weight\":40},\"created_at\":\"2026-11-19T01:42:10.184000Z\",\"entity\":\"rule:R1_VELOCITY\"}");
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, "analyst-1", "RULE_UPDATED", "rule:R1_VELOCITY", before, after, at))
                .isEqualTo("3313d804f7a818342e8674c77657cce85e3445b4031914a77aa6a55ac7b51acc");
    }

    @Test
    void anyEditChangesTheHash() {
        String original = AuditHasher.hash(AuditHasher.GENESIS, "a", "X", "e", before, after, at);
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, "b", "X", "e", before, after, at)).isNotEqualTo(original);
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, "a", "X", "e", before, before, at)).isNotEqualTo(original);
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, "a", "X", "e", before, after, at.plusNanos(1000)))
                .isNotEqualTo(original);
        assertThat(AuditHasher.hash("1".repeat(64), "a", "X", "e", before, after, at)).isNotEqualTo(original);
    }

    @Test
    void nullAndAbsentBeforeHashTheSame() {
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, "a", "X", "e", null, after, at))
                .isEqualTo(AuditHasher.hash(AuditHasher.GENESIS, "a", "X", "e",
                        tools.jackson.databind.node.JsonNodeFactory.instance.nullNode(), after, at));
    }
}
