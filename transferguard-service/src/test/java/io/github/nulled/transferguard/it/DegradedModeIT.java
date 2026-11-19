package io.github.nulled.transferguard.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Redis unreachable: screening keeps answering in degraded mode; idempotency falls back to PostgreSQL. */
class DegradedModeIT extends IntegrationTest {

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("it.redis-url", () -> "redis://127.0.0.1:1");
    }

    @Test
    void screensWithPostgresRulesAndRaisesTheFloor() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        Response small = call("POST", "/screenings", transfer(txn(), account(), account(), "200.00"));
        assertThat(small.status()).isEqualTo(201);
        assertThat(small.body().get("degraded").asBoolean()).isTrue();
        assertThat(small.body().get("decision").asString()).isEqualTo("ALLOW");
        assertThat(reasonCodes(small)).containsExactly("DEGRADED_SCREENING");
        assertThat(small.body().at("/reasons/0/message").asString())
                .contains("R1_VELOCITY", "R5_PASS_THROUGH", "R6_NEW_DEVICE");

        Response large = call("POST", "/screenings", transfer(txn(), account(), account(), "1500.00"));
        assertThat(large.body().get("decision").asString()).isEqualTo("CHALLENGE");
    }

    @Test
    void idempotencyFallsBackToTheUniqueConstraint() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        var request = transfer(txn(), account(), account(), "10.00");
        assertThat(call("POST", "/screenings", request).status()).isEqualTo(201);
        Response replay = call("POST", "/screenings", request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replay")).isEqualTo("true");
        request.put("amount", "11.00");
        assertThat(call("POST", "/screenings", request).status()).isEqualTo(422);
    }

    @Test
    void eventsNeedRedisAndSay503() {
        Response r = call("POST", "/events/security", Map.of("eventId", "E", "account", account(),
                "type", "PASSWORD_RESET", "occurredAt", CLOCK.local()));
        assertThat(r.status()).isEqualTo(503);
    }
}
