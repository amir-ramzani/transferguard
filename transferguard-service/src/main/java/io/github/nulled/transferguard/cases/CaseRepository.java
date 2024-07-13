package io.github.nulled.transferguard.cases;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.common.Times;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CaseRepository {

    public record CaseRow(long id, FraudCase view, String payeeAccount) {}

    private static final String SELECT = """
            SELECT c.*, s.payee_account FROM fraud_case c JOIN screening s ON s.id = c.screening_id""";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String open(UUID screeningId, int score, BigDecimal amount, Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO fraud_case (id, case_no, screening_id, status, score, amount, created_at)
                SELECT n, 'CASE-' || lpad(n::text, 6, '0'), ?, 'OPEN', ?, ?, ?
                FROM (SELECT nextval('fraud_case_seq') AS n) seq
                RETURNING case_no""")
                .params(screeningId, score, amount, utc(createdAt))
                .query(String.class).single();
    }

    public Optional<CaseRow> find(String caseNo, boolean forUpdate) {
        return jdbc.sql(SELECT + " WHERE c.case_no = ?" + (forUpdate ? " FOR UPDATE OF c" : ""))
                .param(caseNo).query(this::map).optional();
    }

    public long count(CaseStatus status, String assignee) {
        return jdbc.sql("""
                SELECT count(*) FROM fraud_case
                WHERE (CAST(? AS varchar) IS NULL OR status = ?) AND (CAST(? AS varchar) IS NULL OR assignee = ?)""")
                .params(name(status), name(status), assignee, assignee).query(Long.class).single();
    }

    public List<FraudCase> page(CaseStatus status, String assignee, int page, int size) {
        return jdbc.sql(SELECT + """
                 WHERE (CAST(? AS varchar) IS NULL OR c.status = ?) AND (CAST(? AS varchar) IS NULL OR c.assignee = ?)
                ORDER BY c.created_at, c.id LIMIT ? OFFSET ?""")
                .params(name(status), name(status), assignee, assignee, size, (long) page * size)
                .query(this::map).list().stream().map(CaseRow::view).toList();
    }

    public long countOpen() {
        return jdbc.sql("SELECT count(*) FROM fraud_case WHERE status = 'OPEN'").query(Long.class).single();
    }

    public void assign(long id, String assignee) {
        jdbc.sql("UPDATE fraud_case SET assignee = ? WHERE id = ?").params(assignee, id).update();
    }

    public void resolve(long id, CaseStatus status, String actor, String note, Instant at) {
        jdbc.sql("""
                UPDATE fraud_case SET status = ?, resolved_at = ?, resolved_by = ?, resolution_note = ?
                WHERE id = ? AND status = 'OPEN'""")
                .params(status.name(), utc(at), actor, note, id).update();
    }

    public CaseNote addNote(long caseId, String author, String note, Instant at) {
        long id = jdbc.sql("INSERT INTO case_note (case_id, author, note, created_at) VALUES (?, ?, ?, ?) RETURNING id")
                .params(caseId, author, note, utc(at)).query(Long.class).single();
        return new CaseNote(id, author, note, Times.local(Times.millis(at)));
    }

    public List<CaseNote> notes(long caseId) {
        return jdbc.sql("SELECT id, author, note, created_at FROM case_note WHERE case_id = ? ORDER BY id")
                .param(caseId)
                .query((rs, n) -> new CaseNote(rs.getLong("id"), rs.getString("author"), rs.getString("note"),
                        Times.local(rs.getObject("created_at", OffsetDateTime.class).toInstant())))
                .list();
    }

    private CaseRow map(ResultSet rs, int n) throws SQLException {
        OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
        FraudCase view = new FraudCase(rs.getString("case_no"), rs.getObject("screening_id", UUID.class),
                CaseStatus.valueOf(rs.getString("status")), rs.getString("assignee"), rs.getInt("score"),
                Money.toWire(rs.getBigDecimal("amount")),
                Times.local(rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                resolved == null ? null : Times.local(resolved.toInstant()));
        return new CaseRow(rs.getLong("id"), view, rs.getString("payee_account"));
    }

    private static String name(CaseStatus s) {
        return s == null ? null : s.name();
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
