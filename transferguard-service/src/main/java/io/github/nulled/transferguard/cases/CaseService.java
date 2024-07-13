package io.github.nulled.transferguard.cases;

import io.github.nulled.transferguard.audit.AuditWriter;
import io.github.nulled.transferguard.common.AfterCommit;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.PageResponse;
import io.github.nulled.transferguard.watchlist.WatchlistEntryRequest;
import io.github.nulled.transferguard.watchlist.WatchlistService;
import io.github.nulled.transferguard.watchlist.WatchlistSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Case lifecycle: a HOLD opens a case as OPEN; an analyst assigns it, then resolves it as
 * CONFIRMED_FRAUD or FALSE_POSITIVE. Resolution is final; corrections are new notes.
 */
@Service
public class CaseService {

    private final CaseRepository repository;
    private final ScreeningLookup screenings;
    private final WatchlistService watchlist;
    private final AuditWriter audit;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicLong openCases = new AtomicLong();

    public CaseService(CaseRepository repository, ScreeningLookup screenings, WatchlistService watchlist,
                       AuditWriter audit, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.screenings = screenings;
        this.watchlist = watchlist;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
        Gauge.builder("transferguard.cases.open", openCases, AtomicLong::get)
                .description("Cases waiting for an analyst").register(meters);
    }

    /** Called inside the screening transaction when the decision is HOLD. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String openForHold(UUID screeningId, int score, BigDecimal amount, Instant createdAt) {
        String caseNo = repository.open(screeningId, score, amount, createdAt);
        AfterCommit.run(openCases::incrementAndGet);
        return caseNo;
    }

    @Transactional(readOnly = true)
    public PageResponse<FraudCase> list(CaseStatus status, String assignee, int page, int size) {
        return new PageResponse<>(page, size, repository.count(status, assignee),
                repository.page(status, assignee, page, size));
    }

    @Transactional(readOnly = true)
    public CaseDetail get(String caseNo) {
        CaseRepository.CaseRow row = load(caseNo, false);
        var screening = screenings.find(row.view().screeningId())
                .orElseThrow(() -> new IllegalStateException("Case " + caseNo + " has no screening"));
        return CaseDetail.of(row.view(), screening, repository.notes(row.id()));
    }

    @Transactional
    public FraudCase update(String caseNo, CaseUpdate update, String actor) {
        validate(update);
        CaseRepository.CaseRow row = load(caseNo, true);
        FraudCase before = row.view();
        if (before.status() != CaseStatus.OPEN) {
            throw ApiException.conflict("Case " + caseNo + " is resolved as " + before.status()
                    + "; resolution is final. Add a note instead.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String entity = "case:" + caseNo;
        if (update.assignee() != null && !update.assignee().equals(before.assignee())) {
            repository.assign(row.id(), update.assignee());
            audit.append(actor, "CASE_ASSIGNED", entity, map("assignee", before.assignee()),
                    map("assignee", update.assignee()));
        }
        if (update.status() != null) {
            repository.resolve(row.id(), update.status().status(), actor, update.resolutionNote(), now);
            repository.addNote(row.id(), actor, update.resolutionNote(), now);
            Map<String, Object> after = map("status", update.status().name());
            after.put("resolutionNote", update.resolutionNote());
            after.put("addPayeeToWatchlist", Boolean.TRUE.equals(update.addPayeeToWatchlist()));
            audit.append(actor, "CASE_RESOLVED", entity, map("status", before.status().name()), after);
            if (Boolean.TRUE.equals(update.addPayeeToWatchlist())) {
                watchlist.put(row.payeeAccount(), new WatchlistEntryRequest(WatchlistSource.CASE,
                        "Confirmed fraud in " + caseNo + ": " + truncate(update.resolutionNote(), 400), caseNo), actor);
            }
            String outcome = update.status().name();
            AfterCommit.run(() -> {
                openCases.updateAndGet(v -> Math.max(0, v - 1));
                meters.counter("transferguard.case.resolutions", "outcome", outcome).increment();
            });
        }
        return load(caseNo, false).view();
    }

    @Transactional
    public CaseNote addNote(String caseNo, CaseNoteRequest request, String actor) {
        CaseRepository.CaseRow row = load(caseNo, true);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        CaseNote note = repository.addNote(row.id(), actor, request.note(), now);
        audit.append(actor, "CASE_NOTE_ADDED", "case:" + caseNo, null, Map.of("noteId", note.noteId(),
                "note", request.note()));
        return note;
    }

    /** Keeps the queue-depth gauge honest across pods and restarts. */
    @Scheduled(fixedDelayString = "${transferguard.cases.gauge-refresh:PT15S}")
    public void refreshOpenGauge() {
        try {
            openCases.set(repository.countOpen());
        } catch (RuntimeException ignored) {
            // PostgreSQL down: keep the last value; readiness reports the outage.
        }
    }

    private CaseRepository.CaseRow load(String caseNo, boolean forUpdate) {
        return repository.find(caseNo, forUpdate).orElseThrow(() -> ApiException.notFound("No case " + caseNo));
    }

    private static void validate(CaseUpdate u) {
        if (u.assignee() == null && u.status() == null && u.resolutionNote() == null && u.addPayeeToWatchlist() == null) {
            throw ApiException.badRequest("", "set assignee, or status with resolutionNote");
        }
        if (u.status() != null && (u.resolutionNote() == null || u.resolutionNote().isBlank())) {
            throw ApiException.badRequest("resolutionNote", "is required when status is set");
        }
        if (u.status() == null && u.resolutionNote() != null) {
            throw ApiException.badRequest("status", "is required with resolutionNote");
        }
        if (Boolean.TRUE.equals(u.addPayeeToWatchlist()) && u.status() != Resolution.CONFIRMED_FRAUD) {
            throw ApiException.badRequest("addPayeeToWatchlist", "is only allowed with status CONFIRMED_FRAUD");
        }
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
