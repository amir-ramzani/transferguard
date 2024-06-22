package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.features.Keys;
import io.github.nulled.transferguard.features.RedisGateway;
import io.github.nulled.transferguard.features.RedisUnavailableException;
import io.lettuce.core.SetArgs;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code tg:idem:{txnId}} holds PENDING while a screening runs, then the screening ID, for 24 hours.
 * If Redis is unavailable the unique constraint on {@code screening.transaction_id} is the guard.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);
    static final String PENDING = "PENDING";
    static final Duration TTL = Duration.ofHours(24);

    public enum State { CLAIMED, IN_PROGRESS, DONE, UNAVAILABLE }

    public record Claim(State state, UUID screeningId) {}

    private final RedisGateway redis;

    public IdempotencyStore(RedisGateway redis) {
        this.redis = redis;
    }

    /** {@code SET key PENDING NX GET EX 86400}: claims the key, or returns what is already there. */
    public Claim claim(String txnId) {
        try {
            String previous = redis.call(c -> c.setGet(Keys.idempotency(txnId), PENDING,
                    SetArgs.Builder.nx().ex(TTL.toSeconds())));
            if (previous == null) {
                return new Claim(State.CLAIMED, null);
            }
            if (PENDING.equals(previous)) {
                return new Claim(State.IN_PROGRESS, null);
            }
            return new Claim(State.DONE, UUID.fromString(previous));
        } catch (RedisUnavailableException e) {
            return new Claim(State.UNAVAILABLE, null);
        } catch (IllegalArgumentException e) {
            log.warn("Unreadable idempotency value for a transaction; relying on the database");
            return new Claim(State.UNAVAILABLE, null);
        }
    }

    public void complete(String txnId, UUID screeningId) {
        try {
            redis.call(c -> c.set(Keys.idempotency(txnId), screeningId.toString(), SetArgs.Builder.xx().keepttl()));
        } catch (RedisUnavailableException e) {
            log.debug("Could not mark idempotency key complete: {}", e.getMessage());
        }
    }

    public void release(String txnId) {
        try {
            redis.call(c -> c.del(Keys.idempotency(txnId)));
        } catch (RedisUnavailableException e) {
            log.debug("Could not release idempotency key: {}", e.getMessage());
        }
    }
}
