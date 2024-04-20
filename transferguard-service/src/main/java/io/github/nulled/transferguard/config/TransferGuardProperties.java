package io.github.nulled.transferguard.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("transferguard")
public record TransferGuardProperties(
        @DefaultValue Security security,
        @DefaultValue Redis redis,
        @DefaultValue Screening screening,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("16384") int maxRequestBytes) {

    /**
     * JWT checks. Locally the mock issuer stamps {@code iss} with the host the token was requested
     * from, so several issuers may be trusted; production trusts exactly one Cognito issuer.
     */
    public record Security(
            @DefaultValue("http://localhost:8090/transferguard") List<String> issuers,
            @DefaultValue("http://localhost:8090/transferguard/jwks") String jwkSetUri,
            @DefaultValue List<String> allowedClientIds,
            @DefaultValue("PT15M") Duration maxTokenLifetime,
            @DefaultValue("true") boolean requireTokenUse) {}

    public record Redis(
            @DefaultValue("redis://localhost:6379") String url,
            @DefaultValue("PT0.05S") Duration commandTimeout,
            @DefaultValue("PT2S") Duration bulkTimeout,
            @DefaultValue("PT0.5S") Duration connectTimeout,
            @DefaultValue("50") float breakerFailureRate,
            @DefaultValue("20") int breakerWindow,
            @DefaultValue("PT10S") Duration breakerWaitOpen,
            @DefaultValue("P400D") Duration payeeStartupLookback) {}

    public record Screening(@DefaultValue("PT5M") Duration maxClockSkew) {}

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") long requestsPerSecond,
            @DefaultValue("1000") long burst) {}
}
