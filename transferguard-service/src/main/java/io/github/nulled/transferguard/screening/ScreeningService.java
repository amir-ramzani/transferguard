package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.cases.CaseService;
import io.github.nulled.transferguard.common.AfterCommit;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.common.Times;
import io.github.nulled.transferguard.config.TransferGuardProperties;
import io.github.nulled.transferguard.features.FeatureLoader;
import io.github.nulled.transferguard.rules.ActiveRule;
import io.github.nulled.transferguard.rules.EngineSettings;
import io.github.nulled.transferguard.rules.FailMode;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.RuleConfigService;
import io.github.nulled.transferguard.rules.RuleEngine;
import io.github.nulled.transferguard.rules.RuleHit;
import io.github.nulled.transferguard.rules.ScreeningContext;
import io.github.nulled.transferguard.rules.Transfer;
import io.github.nulled.transferguard.screening.api.Decision;
import io.github.nulled.transferguard.screening.api.Reason;
import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import io.github.nulled.transferguard.screening.api.ScreeningRequest;
import io.github.nulled.transferguard.screening.api.ScreeningResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The screening pipeline: validate, claim the idempotency key, load rules and features (one Redis
 * round trip), evaluate in memory, decide, persist in one transaction, then mark the key done.
 * A decision is never returned unless it was recorded.
 */
@Service
public class ScreeningService {

    public record Result(ScreeningResponse response, boolean replay) {}

    private record Persisted(boolean inserted, String caseId) {}

    private final RuleConfigService rules;
    private final RuleEngine engine;
    private final FeatureLoader features;
    private final DecisionPolicy policy;
    private final ScreeningRepository repository;
    private final CaseService cases;
    private final IdempotencyStore idempotency;
    private final RequestHasher hasher;
    private final TransactionTemplate tx;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Duration maxSkew;

    public ScreeningService(RuleConfigService rules, RuleEngine engine, FeatureLoader features, DecisionPolicy policy,
                            ScreeningRepository repository, CaseService cases, IdempotencyStore idempotency,
                            RequestHasher hasher, TransactionTemplate tx, ObservationRegistry observations,
                            MeterRegistry meters, Clock clock, TransferGuardProperties props) {
        this.rules = rules;
        this.engine = engine;
        this.features = features;
        this.policy = policy;
        this.repository = repository;
        this.cases = cases;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.tx = tx;
        this.observations = observations;
        this.meters = meters;
        this.clock = clock;
        this.maxSkew = props.screening().maxClockSkew();
    }

    public ScreeningDetail get(UUID screeningId) {
        return repository.findById(screeningId, false).map(ScreeningRepository.Row::detail)
                .orElseThrow(() -> ApiException.notFound("No screening " + screeningId));
    }

    public Result screen(ScreeningRequest request) {
        Instant now = clock.instant();
        BigDecimal amount = validate(request, now);
        String hash = hasher.hash(request);

        IdempotencyStore.Claim claim = idempotency.claim(request.transactionId());
        switch (claim.state()) {
            case IN_PROGRESS -> throw new ApiException(HttpStatus.CONFLICT, "idempotency-in-progress",
                    "Screening in progress", "This transactionId is being screened right now.",
                    Map.of("Retry-After", "1"), null);
            case DONE -> {
                var stored = repository.findById(claim.screeningId(), false);
                if (stored.isPresent()) {
                    return replay(stored.get(), hash);
                }
                // Key outlived its row (e.g. a restored database): screen again; the unique constraint guards.
            }
            default -> { }
        }
        boolean claimed = claim.state() == IdempotencyStore.State.CLAIMED;
        try {
            Result result = screenClaimed(request, amount, hash, now);
            if (claimed && result.replay()) {
                idempotency.complete(request.transactionId(), result.response().screeningId());
            }
            return result;
        } catch (RuntimeException e) {
            if (claimed) {
                idempotency.release(request.transactionId());
            }
            throw e;
        }
    }

    private Result screenClaimed(ScreeningRequest request, BigDecimal amount, String hash, Instant now) {
        List<ActiveRule> active = rules.activeRules();
        EngineSettings settings = rules.settings();
        Transfer transfer = new Transfer(request.transactionId(), request.sourceAccount(), request.payeeAccount(),
                amount, request.deviceId(), request.requestedAt().toInstant());
        FeatureNeeds needs = engine.needs(active);

        FeatureLoader.Loaded loaded = Observation.createNotStarted("screening.features", observations)
                .observe(() -> features.load(transfer, needs, now, settings.failMode()));
        boolean degraded = loaded.degraded();
        ScreeningContext ctx = new ScreeningContext(transfer, loaded.features(), now);

        Observation rulesObs = Observation.createNotStarted("screening.rules", observations).start();
        RuleEngine.Evaluation eval;
        try {
            eval = degraded && settings.failMode() == FailMode.OPEN
                    ? new RuleEngine.Evaluation(List.of(), active.stream().map(ActiveRule::code).toList(), 0)
                    : engine.evaluate(active, ctx);
            rulesObs.highCardinalityKeyValue("score", Integer.toString(eval.score()));
            rulesObs.highCardinalityKeyValue("rules.hit",
                    eval.hits().stream().map(RuleHit::code).collect(Collectors.joining(",")));
            ctx.notes().forEach(rulesObs::highCardinalityKeyValue);
        } finally {
            rulesObs.stop();
        }

        List<RuleHit> reasons = new ArrayList<>(eval.hits());
        if (degraded) {
            reasons.add(new RuleHit(RuleHit.DEGRADED_SCREENING, 0, degradedMessage(settings.failMode(), eval.skipped())));
        }
        Decision decision = policy.decide(eval.score(), degraded, amount, settings);
        UUID id = UUID.randomUUID();
        Instant screenedAt = Times.millis(now);

        Persisted persisted = Observation.createNotStarted("screening.persist", observations)
                .highCardinalityKeyValue("decision", decision.name())
                .observe(() -> tx.execute(status -> {
                    boolean inserted = repository.insert(new ScreeningRepository.NewScreening(id, request, amount, hash,
                            eval.score(), decision, degraded, screenedAt));
                    if (!inserted) {
                        return new Persisted(false, null);
                    }
                    repository.insertReasons(id, reasons);
                    String caseId = decision == Decision.HOLD
                            ? cases.openForHold(id, eval.score(), amount, screenedAt) : null;
                    AfterCommit.run(() -> {
                        idempotency.complete(request.transactionId(), id);
                        record(decision, degraded, eval.hits());
                    });
                    return new Persisted(true, caseId);
                }));

        if (!persisted.inserted()) {
            // Redis lost the key (degraded mode or expiry); PostgreSQL's unique constraint caught the repeat.
            var stored = repository.findByTransactionId(request.transactionId())
                    .orElseThrow(() -> ApiException.conflict("Transaction is being stored; retry."));
            return replay(stored, hash);
        }
        List<Reason> reasonViews = reasons.stream().map(h -> new Reason(h.code(), h.points(), h.message())).toList();
        return new Result(new ScreeningResponse(id, request.transactionId(), decision, eval.score(), degraded,
                reasonViews, persisted.caseId(), Times.local(screenedAt)), false);
    }

    private Result replay(ScreeningRepository.Row stored, String hash) {
        if (!stored.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-mismatch",
                    "Transaction ID reused", "This transactionId was already screened with a different request body.",
                    null, null);
        }
        return new Result(stored.detail().toResponse(), true);
    }

    private BigDecimal validate(ScreeningRequest request, Instant now) {
        BigDecimal amount = Money.parse(request.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount", "must be greater than 0.00");
        }
        Duration skew = Duration.between(request.requestedAt().toInstant(), now).abs();
        if (skew.compareTo(maxSkew) > 0) {
            throw ApiException.badRequest("requestedAt",
                    "must be within " + maxSkew.toMinutes() + " minutes of server time");
        }
        return amount;
    }

    private void record(Decision decision, boolean degraded, List<RuleHit> hits) {
        meters.counter("transferguard.screenings", "decision", decision.name(), "degraded", Boolean.toString(degraded))
                .increment();
        for (RuleHit hit : hits) {
            meters.counter("transferguard.rule.hits", "rule", hit.code()).increment();
        }
    }

    private static String degradedMessage(FailMode mode, List<RuleCode> skipped) {
        String rules = skipped.isEmpty() ? "none" : skipped.stream().map(Enum::name).collect(Collectors.joining(", "));
        return switch (mode) {
            case OPEN -> "Real-time features unavailable; fail-open mode, no rules ran";
            case DEGRADED -> "Real-time features unavailable; skipped " + rules;
            case CLOSED -> "Real-time features unavailable; skipped " + rules + "; fail-closed mode challenges every transfer";
        };
    }
}
