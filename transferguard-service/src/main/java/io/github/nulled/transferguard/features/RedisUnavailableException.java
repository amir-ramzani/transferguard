package io.github.nulled.transferguard.features;

/** Redis is slow, down, or the circuit breaker is open. Callers fall back or answer 503. */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
