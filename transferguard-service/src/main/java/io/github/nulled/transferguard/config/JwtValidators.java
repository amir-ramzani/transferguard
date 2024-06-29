package io.github.nulled.transferguard.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * Cognito-style access token checks: signature (by the decoder), expiry, an issuer allow-list,
 * {@code token_use = access}, a {@code client_id} allow-list (Cognito access tokens have no
 * {@code aud}) and a maximum token lifetime.
 */
public final class JwtValidators {

    private JwtValidators() {}

    public static OAuth2TokenValidator<Jwt> of(TransferGuardProperties.Security s) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(30)));
        validators.add(issuer(Set.copyOf(s.issuers())));
        if (s.requireTokenUse()) {
            validators.add(claimEquals("token_use", "access"));
        }
        if (!s.allowedClientIds().isEmpty()) {
            validators.add(clientId(Set.copyOf(s.allowedClientIds())));
        }
        validators.add(lifetime(s.maxTokenLifetime()));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    static OAuth2TokenValidator<Jwt> issuer(Set<String> issuers) {
        return jwt -> {
            String iss = jwt.getClaimAsString("iss");
            return iss != null && issuers.contains(iss) ? OAuth2TokenValidatorResult.success()
                    : failure("The token issuer is not trusted");
        };
    }

    static OAuth2TokenValidator<Jwt> claimEquals(String claim, String expected) {
        return jwt -> expected.equals(jwt.getClaimAsString(claim)) ? OAuth2TokenValidatorResult.success()
                : failure("The " + claim + " claim must be " + expected);
    }

    static OAuth2TokenValidator<Jwt> clientId(Set<String> allowed) {
        return jwt -> {
            String clientId = jwt.getClaimAsString("client_id");
            return clientId != null && allowed.contains(clientId) ? OAuth2TokenValidatorResult.success()
                    : failure("The client is not allowed");
        };
    }

    static OAuth2TokenValidator<Jwt> lifetime(Duration max) {
        return jwt -> {
            Instant iat = jwt.getIssuedAt();
            Instant exp = jwt.getExpiresAt();
            if (iat == null || exp == null) {
                return failure("The token must carry iat and exp");
            }
            return Duration.between(iat, exp).compareTo(max.plusSeconds(5)) <= 0 ? OAuth2TokenValidatorResult.success()
                    : failure("The token lives longer than " + max);
        };
    }

    private static OAuth2TokenValidatorResult failure(String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
    }
}
