package io.github.nulled.transferguard.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RuleRepository {

    public record RuleRow(String code, String name, boolean enabled, int weight, JsonNode params, int version,
                          Instant updatedAt, String updatedBy) {}

    public record SettingRow(JsonNode value, int version, Instant updatedAt, String updatedBy) {}

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public RuleRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<RuleRow> findAll() {
        return jdbc.sql("""
                SELECT code, name, enabled, weight, params::text AS params, version, updated_at, updated_by
                FROM rule ORDER BY code""").query(this::mapRule).list();
    }

    public Optional<RuleRow> find(String code) {
        return jdbc.sql("""
                SELECT code, name, enabled, weight, params::text AS params, version, updated_at, updated_by
                FROM rule WHERE code = ?""").param(code).query(this::mapRule).optional();
    }

    /** Optimistic lock: returns false when the version moved on. */
    public boolean update(String code, boolean enabled, int weight, JsonNode params, int expectedVersion,
                          String actor, Instant now) {
        return jdbc.sql("""
                UPDATE rule SET enabled = ?, weight = ?, params = ?::jsonb, version = version + 1,
                                updated_at = ?, updated_by = ?
                WHERE code = ? AND version = ?""")
                .params(enabled, weight, mapper.writeValueAsString(params), OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                        actor, code, expectedVersion)
                .update() == 1;
    }

    public Optional<SettingRow> findSetting(String key) {
        return jdbc.sql("SELECT value::text AS value, version, updated_at, updated_by FROM engine_setting WHERE key = ?")
                .param(key)
                .query((rs, n) -> new SettingRow(mapper.readTree(rs.getString("value")), rs.getInt("version"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by")))
                .optional();
    }

    public boolean updateSetting(String key, JsonNode value, int expectedVersion, String actor, Instant now) {
        return jdbc.sql("""
                UPDATE engine_setting SET value = ?::jsonb, version = version + 1, updated_at = ?, updated_by = ?
                WHERE key = ? AND version = ?""")
                .params(mapper.writeValueAsString(value), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), actor, key,
                        expectedVersion)
                .update() == 1;
    }

    private RuleRow mapRule(ResultSet rs, int n) throws SQLException {
        return new RuleRow(rs.getString("code"), rs.getString("name"), rs.getBoolean("enabled"), rs.getInt("weight"),
                mapper.readTree(rs.getString("params")), rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by"));
    }
}
