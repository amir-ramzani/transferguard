package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AuditRepository {

    /** Fixed key for pg_advisory_xact_lock: serialises writers so the chain can't fork. */
    static final long CHAIN_LOCK_KEY = 0x7467_6175_6469_74L;

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;
    private final JsonMapper mapper;

    public AuditRepository(JdbcClient jdbc, JdbcTemplate template, JsonMapper mapper) {
        this.jdbc = jdbc;
        // A private template so the fetch size doesn't leak into the shared one.
        this.streaming = new JdbcTemplate(template.getDataSource());
        this.streaming.setFetchSize(500);
        this.mapper = mapper;
    }

    void lockChain() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(CHAIN_LOCK_KEY).query((rs, n) -> 1).list();
    }

    Optional<String> latestHash() {
        return jdbc.sql("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1").query(String.class).optional();
    }

    long insert(String actor, String action, String entity, JsonNode before, JsonNode after, String prevHash,
                String hash, Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO audit_log (actor, action, entity, before, after, prev_hash, hash, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?) RETURNING id""")
                .params(actor, action, entity, json(before), json(after), prevHash, hash,
                        OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC))
                .query(Long.class).single();
    }

    long count(String entity) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE (CAST(? AS varchar) IS NULL OR entity = ?)")
                .params(entity, entity).query(Long.class).single();
    }

    List<AuditEntry> page(String entity, int page, int size) {
        return jdbc.sql("""
                SELECT id, actor, action, entity, before::text AS before, after::text AS after, prev_hash, hash, created_at
                FROM audit_log WHERE (CAST(? AS varchar) IS NULL OR entity = ?)
                ORDER BY id DESC LIMIT ? OFFSET ?""")
                .params(entity, entity, size, (long) page * size)
                .query(this::map).list();
    }

    /** Streams the whole chain in id order without loading it into memory. */
    void streamAll(Consumer<AuditEntry> consumer) {
        streaming.query("""
                SELECT id, actor, action, entity, before::text AS before, after::text AS after, prev_hash, hash, created_at
                FROM audit_log ORDER BY id""", (RowCallbackHandler) rs -> consumer.accept(map(rs, 0)));
    }

    private AuditEntry map(ResultSet rs, int n) throws SQLException {
        return new AuditEntry(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                rs.getString("entity"), node(rs.getString("before")), node(rs.getString("after")),
                rs.getString("prev_hash"), rs.getString("hash"),
                Times.local(rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    private JsonNode node(String text) {
        return text == null ? null : mapper.readTree(text);
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() ? null : mapper.writeValueAsString(node);
    }
}
