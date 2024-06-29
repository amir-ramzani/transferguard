package io.github.nulled.transferguard.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtValidatorsTest {

    private final TransferGuardProperties.Security config = new TransferGuardProperties.Security(
            List.of("http://localhost:8090/transferguard", "http://mock-oauth2:8080/transferguard"),
            "http://unused/jwks", List.of("transferguard-simulator"), Duration.ofMinutes(15), true);

    private Jwt.Builder token() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://mock-oauth2:8080/transferguard")
                .claim("token_use", "access").claim("client_id", "transferguard-simulator")
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(15)));
    }

    private boolean valid(Jwt jwt) {
        return !JwtValidators.of(config).validate(jwt).hasErrors();
    }

    @Test
    void acceptsAGoodAccessToken() {
        assertThat(valid(token().build())).isTrue();
        assertThat(valid(token().issuer("http://localhost:8090/transferguard").build())).isTrue();
    }

    @Test
    void rejectsUntrustedIssuer() {
        assertThat(valid(token().issuer("https://evil.example/transferguard").build())).isFalse();
    }

    @Test
    void rejectsIdTokens() {
        assertThat(valid(token().claim("token_use", "id").build())).isFalse();
    }

    @Test
    void rejectsUnknownClients() {
        assertThat(valid(token().claim("client_id", "someone-else").build())).isFalse();
    }

    @Test
    void rejectsLongLivedTokens() {
        Instant now = Instant.now();
        assertThat(valid(token().issuedAt(now).expiresAt(now.plus(Duration.ofHours(1))).build())).isFalse();
    }

    @Test
    void rejectsExpiredTokens() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        assertThat(valid(token().issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(10))).build())).isFalse();
    }
}
