package io.github.nulled.transferguard.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.nulled.transferguard.common.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token bucket per OAuth client ID (Bucket4j). Runs after authentication, so the bucket key is a
 * verified claim. Per pod: the limit scales with replicas, which is fine for abuse protection.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TransferGuardProperties.RateLimit config;
    private final Problems problems;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10)).maximumSize(10_000).build();

    public RateLimitFilter(TransferGuardProperties.RateLimit config, Problems problems) {
        this.config = config;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(Actor.clientId(), k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(config.burst())
                        .refillGreedy(config.requestsPerSecond(), Duration.ofSeconds(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retry = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                "Rate limit for this client exceeded.", Map.of("Retry-After", Long.toString(retry)));
    }
}
