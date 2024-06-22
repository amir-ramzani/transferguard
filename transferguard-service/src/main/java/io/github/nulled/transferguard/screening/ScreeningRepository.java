package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.common.Times;
import io.github.nulled.transferguard.rules.RuleHit;
import io.github.nulled.transferguard.screening.api.Channel;
import io.github.nulled.transferguard.screening.api.Currency;
import io.github.nulled.transferguard.screening.api.Decision;
import io.github.nulled.transferguard.screening.api.Reason;
import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import io.github.nulled.transferguard.screening.api.ScreeningRequest;
import io.github.nulled.transferguard.screening.api.TransferOutcome;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScreeningRepository {

    public record NewScreening(UUID id, ScreeningRequest request, BigDecimal amount, String requestHash, int score,
                               Decision decision, boolean degraded, Instant createdAt) {}

    /** The stored row, with what the outcome endpoint needs. */
    public record Row(ScreeningDetail detail, String requestHash, String sourceAccount, String payeeAccount,
                      BigDecimal amount) {}

    private static final String SELECT = """
            SELECT s.*, c.case_no FROM screening s LEFT JOIN fraud_case c ON c.screening_id = s.id""";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ScreeningRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** Returns false if the transaction ID is already stored (the final idempotency guard). */
    public boolean insert(NewScreening s) {
        ScreeningRequest r = s.request();
        return !jdbc.sql("""
                INSERT INTO screening (id, transaction_id, request_hash, source_account, payee_account, amount,
                                       currency, channel, device_id, score, decision, degraded, requested_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING
                RETURNING id""")
                .params(s.id(), r.transactionId(), s.requestHash(), r.sourceAccount(), r.payeeAccount(), s.amount(),
                        r.currency().name(), r.channel().name(), r.deviceId(), s.score(), s.decision().name(),
                        s.degraded(), r.requestedAt(), utc(s.createdAt()))
                .query(UUID.class).list().isEmpty();
    }

    public void insertReasons(UUID screeningId, List<RuleHit> hits) {
        List<Object[]> rows = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            RuleHit h = hits.get(i);
            rows.add(new Object[]{screeningId, i, h.code(), h.points(), h.message()});
        }
        if (!rows.isEmpty()) {
            template.batchUpdate(
                    "INSERT INTO screening_reason (screening_id, position, code, points, message) VALUES (?, ?, ?, ?, ?)",
                    rows);
        }
    }

    public Optional<Row> findById(UUID id, boolean forUpdate) {
        return jdbc.sql(SELECT + " WHERE s.id = ?" + (forUpdate ? " FOR UPDATE OF s" : ""))
                .param(id).query(this::map).optional().map(this::withReasons);
    }

    public Optional<Row> findByTransactionId(String transactionId) {
        return jdbc.sql(SELECT + " WHERE s.transaction_id = ?")
                .param(transactionId).query(this::map).optional().map(this::withReasons);
    }

    public boolean setOutcome(UUID id, TransferOutcome outcome, Instant at) {
        return jdbc.sql("UPDATE screening SET outcome = ?, outcome_at = ? WHERE id = ? AND outcome IS NULL")
                .params(outcome.name(), utc(at), id).update() == 1;
    }

    private Row withReasons(Row row) {
        List<Reason> reasons = jdbc.sql(
                        "SELECT code, points, message FROM screening_reason WHERE screening_id = ? ORDER BY position")
                .param(row.detail().screeningId())
                .query((rs, n) -> new Reason(rs.getString("code"), rs.getInt("points"), rs.getString("message")))
                .list();
        ScreeningDetail d = row.detail();
        return new Row(new ScreeningDetail(d.screeningId(), d.transactionId(), d.decision(), d.score(), d.degraded(),
                reasons, d.caseId(), d.screenedAt(), d.request(), d.outcome(), d.outcomeAt()),
                row.requestHash(), row.sourceAccount(), row.payeeAccount(), row.amount());
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        ScreeningRequest request = new ScreeningRequest(rs.getString("transaction_id"), rs.getString("source_account"),
                rs.getString("payee_account"), Money.toWire(amount), Currency.valueOf(rs.getString("currency").trim()),
                Channel.valueOf(rs.getString("channel")), rs.getString("device_id"),
                Times.local(rs.getObject("requested_at", OffsetDateTime.class).toInstant()));
        String outcome = rs.getString("outcome");
        OffsetDateTime outcomeAt = rs.getObject("outcome_at", OffsetDateTime.class);
        ScreeningDetail detail = new ScreeningDetail(rs.getObject("id", UUID.class), rs.getString("transaction_id"),
                Decision.valueOf(rs.getString("decision")), rs.getInt("score"), rs.getBoolean("degraded"), List.of(),
                rs.getString("case_no"), Times.local(rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                request, outcome == null ? null : TransferOutcome.valueOf(outcome),
                outcomeAt == null ? null : Times.local(outcomeAt.toInstant()));
        return new Row(detail, rs.getString("request_hash"), rs.getString("source_account"),
                rs.getString("payee_account"), amount);
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
