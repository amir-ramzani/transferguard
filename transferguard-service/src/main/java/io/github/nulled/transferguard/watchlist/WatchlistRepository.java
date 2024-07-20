package io.github.nulled.transferguard.watchlist;

import io.github.nulled.transferguard.common.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WatchlistRepository {

    private final JdbcClient jdbc;

    public WatchlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WatchlistEntry> findForUpdate(String accountNo) {
        return jdbc.sql("SELECT * FROM watchlist_entry WHERE account_no = ? FOR UPDATE")
                .param(accountNo).query(this::map).optional();
    }

    public void insert(WatchlistEntry e, Instant addedAt) {
        jdbc.sql("""
                INSERT INTO watchlist_entry (account_no, source, reason, case_id, added_at, added_by)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(e.accountNo(), e.source().name(), e.reason(), e.caseId(),
                        OffsetDateTime.ofInstant(addedAt, ZoneOffset.UTC), e.addedBy())
                .update();
    }

    public void update(WatchlistEntry e) {
        jdbc.sql("UPDATE watchlist_entry SET source = ?, reason = ?, case_id = ? WHERE account_no = ?")
                .params(e.source().name(), e.reason(), e.caseId(), e.accountNo()).update();
    }

    public boolean delete(String accountNo) {
        return jdbc.sql("DELETE FROM watchlist_entry WHERE account_no = ?").param(accountNo).update() == 1;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM watchlist_entry").query(Long.class).single();
    }

    public List<WatchlistEntry> page(int page, int size) {
        return jdbc.sql("SELECT * FROM watchlist_entry ORDER BY added_at DESC, account_no LIMIT ? OFFSET ?")
                .params(size, (long) page * size).query(this::map).list();
    }

    private WatchlistEntry map(ResultSet rs, int n) throws SQLException {
        return new WatchlistEntry(rs.getString("account_no"), WatchlistSource.valueOf(rs.getString("source")),
                rs.getString("reason"), rs.getString("case_id"),
                Times.local(rs.getObject("added_at", OffsetDateTime.class).toInstant()), rs.getString("added_by"));
    }
}
