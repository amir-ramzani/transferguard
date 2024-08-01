package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.common.AfterCommit;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.features.FeatureRecorder;
import io.github.nulled.transferguard.features.RedisUnavailableException;
import io.github.nulled.transferguard.profile.AccountProfileService;
import io.github.nulled.transferguard.screening.api.OutcomeRequest;
import io.github.nulled.transferguard.screening.api.TransferOutcome;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happened to a screened transfer. Only COMPLETED transfers teach the system: the payee becomes
 * known (R2), the 30-day profile grows (R3) and the outbound total for R5 rises.
 */
@Service
public class OutcomeService {

    private static final Logger log = LoggerFactory.getLogger(OutcomeService.class);

    private final ScreeningRepository repository;
    private final AccountProfileService profile;
    private final FeatureRecorder features;

    public OutcomeService(ScreeningRepository repository, AccountProfileService profile, FeatureRecorder features) {
        this.repository = repository;
        this.profile = profile;
        this.features = features;
    }

    @Transactional
    public void record(UUID screeningId, OutcomeRequest request) {
        ScreeningRepository.Row row = repository.findById(screeningId, true)
                .orElseThrow(() -> ApiException.notFound("No screening " + screeningId));
        TransferOutcome current = row.detail().outcome();
        if (current != null) {
            if (current == request.outcome()) {
                return;
            }
            throw ApiException.conflict("Outcome is already " + current + "; a recorded outcome cannot change.");
        }
        Instant occurredAt = request.occurredAt().toInstant().truncatedTo(ChronoUnit.MICROS);
        repository.setOutcome(screeningId, request.outcome(), occurredAt);
        if (request.outcome() == TransferOutcome.COMPLETED) {
            profile.recordCompleted(row.sourceAccount(), row.amount(), occurredAt);
            String txnId = row.detail().transactionId();
            AfterCommit.run(() -> {
                try {
                    features.recordCompleted(row.sourceAccount(), row.payeeAccount(), txnId, row.amount(), occurredAt);
                } catch (RedisUnavailableException e) {
                    // PostgreSQL holds the outcome; the rebuild job replays it into Redis on recovery.
                    log.warn("Completed outcome not mirrored to Redis yet: {}", e.getMessage());
                }
            });
        }
    }
}
