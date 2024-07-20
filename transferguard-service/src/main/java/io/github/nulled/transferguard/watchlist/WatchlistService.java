package io.github.nulled.transferguard.watchlist;

import io.github.nulled.transferguard.audit.AuditWriter;
import io.github.nulled.transferguard.common.AfterCommit;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.PageResponse;
import io.github.nulled.transferguard.common.Pii;
import io.github.nulled.transferguard.common.Times;
import io.github.nulled.transferguard.features.FeatureRecorder;
import io.github.nulled.transferguard.features.RedisUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reported mule accounts. PostgreSQL is the record; the Redis set {@code tg:watchlist} is a mirror
 * updated after commit and fully resynced at startup, on Redis recovery and every five minutes.
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    public record PutResult(WatchlistEntry entry, boolean created) {}

    private final WatchlistRepository repository;
    private final AuditWriter audit;
    private final FeatureRecorder features;
    private final Clock clock;

    public WatchlistService(WatchlistRepository repository, AuditWriter audit, FeatureRecorder features, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.features = features;
        this.clock = clock;
    }

    @Transactional
    public PutResult put(String accountNo, WatchlistEntryRequest request, String actor) {
        Optional<WatchlistEntry> existing = repository.findForUpdate(accountNo);
        PutResult result;
        if (existing.isPresent()) {
            WatchlistEntry before = existing.get();
            WatchlistEntry after = new WatchlistEntry(accountNo, request.source(), request.reason(), request.caseId(),
                    before.addedAt(), before.addedBy());
            repository.update(after);
            audit.append(actor, "WATCHLIST_UPDATED", "watchlist:" + accountNo, before, after);
            result = new PutResult(after, false);
        } else {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            WatchlistEntry entry = new WatchlistEntry(accountNo, request.source(), request.reason(), request.caseId(),
                    Times.local(Times.millis(now)), actor);
            repository.insert(entry, now);
            audit.append(actor, "WATCHLIST_ADDED", "watchlist:" + accountNo, null, entry);
            result = new PutResult(entry, true);
        }
        AfterCommit.run(() -> mirror(() -> features.watchlistAdd(accountNo), accountNo));
        return result;
    }

    @Transactional
    public void remove(String accountNo, String actor) {
        WatchlistEntry before = repository.findForUpdate(accountNo)
                .orElseThrow(() -> ApiException.notFound("Account is not on the watchlist"));
        repository.delete(accountNo);
        audit.append(actor, "WATCHLIST_REMOVED", "watchlist:" + accountNo, before, null);
        AfterCommit.run(() -> mirror(() -> features.watchlistRemove(accountNo), accountNo));
    }

    @Transactional(readOnly = true)
    public PageResponse<WatchlistEntry> list(int page, int size) {
        return new PageResponse<>(page, size, repository.count(), repository.page(page, size));
    }

    private void mirror(Runnable action, String accountNo) {
        try {
            action.run();
        } catch (RedisUnavailableException e) {
            log.warn("Watchlist mirror for {} deferred to the next resync: {}", Pii.mask(accountNo), e.getMessage());
        }
    }
}
