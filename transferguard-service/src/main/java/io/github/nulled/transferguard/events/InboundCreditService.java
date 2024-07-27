package io.github.nulled.transferguard.events;

import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.Money;
import io.github.nulled.transferguard.features.FeatureRecorder;
import io.github.nulled.transferguard.features.RedisUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Inbound credits feed only R5's short window, so they go to Redis and not PostgreSQL. The bank's
 * core system remains the record for them.
 */
@Service
public class InboundCreditService {

    private final FeatureRecorder features;
    private final Clock clock;

    public InboundCreditService(FeatureRecorder features, Clock clock) {
        this.features = features;
        this.clock = clock;
    }

    public void record(InboundCreditEvent event) {
        BigDecimal amount = Money.parse(event.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount", "must be greater than 0.00");
        }
        try {
            features.recordInboundCredit(event.account(), event.creditId(), event.senderAccount(), amount,
                    event.receivedAt().toInstant(), clock.instant());
        } catch (RedisUnavailableException e) {
            throw ApiException.unavailable("The event store is unavailable; retry later.");
        }
    }
}
