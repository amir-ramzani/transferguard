package io.github.nulled.transferguard.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.contract.OpenApi;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;

class ScreeningFlowIT extends IntegrationTest {

    /** README/contract example request → contract example response, through HTTP, Redis and PostgreSQL. */
    @Test
    void goldenExample() {
        CLOCK.set("2026-11-19T01:42:10.184+08:00");
        JsonNode spec = OpenApi.load();
        JsonNode request = spec.at("/paths/~1screenings/post/requestBody/content/application~1json/examples/takeover/value");
        JsonNode expected = spec.at("/components/examples/HoldResponse/value");
        String source = request.get("sourceAccount").asText();

        superuser().update("""
                INSERT INTO account_daily_stats (account_no, day, amount_sum, txn_count) VALUES (?, DATE '2026-11-10', 1800.00, 3)
                ON CONFLICT DO NOTHING""", source);
        Response event = call("POST", "/events/security", Map.of("eventId", "EV-1", "account", source,
                "type", "DEVICE_CHANGED", "deviceId", "dev-7f3a", "occurredAt", "2026-11-19T01:30:10+08:00"));
        assertThat(event.status()).isEqualTo(202);

        Response r = call("POST", "/screenings", request.toString());

        assertThat(r.status()).isEqualTo(201);
        JsonNode body = r.body();
        assertThat(body.get("decision")).isEqualTo(expected.get("decision"));
        assertThat(body.get("score")).isEqualTo(expected.get("score"));
        assertThat(body.get("degraded")).isEqualTo(expected.get("degraded"));
        assertThat(body.get("reasons")).isEqualTo(expected.get("reasons"));
        assertThat(body.get("transactionId")).isEqualTo(expected.get("transactionId"));
        assertThat(body.get("screenedAt")).isEqualTo(expected.get("screenedAt"));
        assertThat(body.get("caseId").asText()).matches("^CASE-[0-9]{6,}$");
        assertThat(r.header("Location")).isEqualTo("/api/v1/screenings/" + body.get("screeningId").asText());
    }

    @Test
    void replayReturnsTheStoredScreeningAndADifferentBodyIs422() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        var request = transfer(txn(), account(), account(), "120.00");
        Response first = call("POST", "/screenings", request);
        Response again = call("POST", "/screenings", request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(again.body()).isEqualTo(first.body());

        request.put("amount", "121.00");
        Response changed = call("POST", "/screenings", request);
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.body().get("type").asText()).endsWith("/idempotency-mismatch");
    }

    @Test
    void twentyParallelRetriesStoreOneRowAndCountOneAttempt() throws Exception {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String source = account();
        var request = transfer(txn(), source, account(), "75.00");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return call("POST", "/screenings", request).status();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).isSubsetOf(201, 200, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(appJdbc.queryForObject("SELECT count(*) FROM screening WHERE transaction_id = ?", Long.class,
                request.get("transactionId"))).isEqualTo(1);
        RedisClient client = RedisClient.create(redisUrl());
        try (var c = client.connect()) {
            assertThat(c.sync().zcard("tg:{" + source + "}:out")).isEqualTo(1);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void sixthTransferInTenMinutesTriggersVelocity() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String source = account();
        String payee = account();
        for (int i = 1; i <= 5; i++) {
            Response r = call("POST", "/screenings", transfer(txn(), source, payee, "20.00"));
            assertThat(reasonCodes(r)).as("attempt " + i).doesNotContain("R1_VELOCITY");
            CLOCK.advance(Duration.ofSeconds(30));
        }
        Response sixth = call("POST", "/screenings", transfer(txn(), source, payee, "20.00"));
        assertThat(reasonCodes(sixth)).contains("R1_VELOCITY");
        assertThat(sixth.body().get("decision").asText()).isEqualTo("CHALLENGE");

        // Ten minutes after the first attempt it has left the window: 5 in window, plus this one = 6 again.
        CLOCK.set("2026-11-19T12:10:00+08:00");
        Response later = call("POST", "/screenings", transfer(txn(), source, payee, "20.00"));
        assertThat(reasonCodes(later)).contains("R1_VELOCITY");
        CLOCK.set("2026-11-19T12:12:31+08:00");
        Response quiet = call("POST", "/screenings", transfer(txn(), source, payee, "20.00"));
        assertThat(reasonCodes(quiet)).doesNotContain("R1_VELOCITY");
    }

    @Test
    void onlyACompletedTransferMakesAPayeeKnown() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String source = account();
        String payee = account();

        Response first = call("POST", "/screenings", transfer(txn(), source, payee, "3500.00"));
        assertThat(reasonCodes(first)).contains("R2_NEW_PAYEE_HIGH_AMOUNT");
        String id = first.body().get("screeningId").asText();
        assertThat(call("POST", "/screenings/" + id + "/outcome",
                Map.of("outcome", "CHALLENGE_FAILED", "occurredAt", CLOCK.local())).status()).isEqualTo(204);

        Response second = call("POST", "/screenings", transfer(txn(), source, payee, "3500.00"));
        assertThat(reasonCodes(second)).as("challenged, never completed: still new").contains("R2_NEW_PAYEE_HIGH_AMOUNT");
        String id2 = second.body().get("screeningId").asText();
        var completed = Map.of("outcome", "COMPLETED", "occurredAt", CLOCK.local());
        assertThat(call("POST", "/screenings/" + id2 + "/outcome", completed).status()).isEqualTo(204);
        assertThat(call("POST", "/screenings/" + id2 + "/outcome", completed).status()).as("same outcome again").isEqualTo(204);
        assertThat(call("POST", "/screenings/" + id2 + "/outcome",
                Map.of("outcome", "CANCELLED", "occurredAt", CLOCK.local())).status()).isEqualTo(409);

        Response third = call("POST", "/screenings", transfer(txn(), source, payee, "3500.00"));
        assertThat(reasonCodes(third)).doesNotContain("R2_NEW_PAYEE_HIGH_AMOUNT");

        Response detail = call("GET", "/screenings/" + id2, null);
        assertThat(detail.body().get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(detail.body().at("/request/amount").asText()).isEqualTo("3500.00");
    }

    @Test
    void muleRingIsHeld() {
        CLOCK.set("2026-11-19T15:00:00+08:00");
        String mule = account();
        String[] victims = {account(), account(), account(), account()};
        String[] amounts = {"2500.00", "3000.00", "1800.00", "2700.00"}; // 10,000.00
        for (int i = 0; i < victims.length; i++) {
            assertThat(call("POST", "/events/inbound-credits", Map.of("creditId", "CR-" + mule + "-" + i,
                    "account", mule, "senderAccount", victims[i], "amount", amounts[i], "currency", "MYR",
                    "receivedAt", CLOCK.local())).status()).isEqualTo(202);
        }
        CLOCK.advance(Duration.ofMinutes(20));
        Response out = call("POST", "/screenings", transfer(txn(), mule, account(), "9100.00"));
        assertThat(reasonCodes(out)).contains("R5_PASS_THROUGH", "R2_NEW_PAYEE_HIGH_AMOUNT");
        assertThat(out.body().get("decision").asText()).isEqualTo("HOLD");
    }

    @Test
    void watchlistedPayeeIsHeldAndRemovalIsAudited() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String payee = account();
        Response put = call("PUT", "/watchlist/" + payee, Map.of("source", "POLICE_REPORT", "reason", "Reported mule"));
        assertThat(put.status()).isEqualTo(201);
        assertThat(call("PUT", "/watchlist/" + payee, Map.of("source", "NFCC", "reason", "Also NFCC")).status())
                .isEqualTo(200);

        Response held = call("POST", "/screenings", transfer(txn(), account(), payee, "50.00"));
        assertThat(reasonCodes(held)).containsExactly("R7_WATCHLIST");
        assertThat(held.body().get("decision").asText()).isEqualTo("HOLD");

        assertThat(call("DELETE", "/watchlist/" + payee, null).status()).isEqualTo(204);
        assertThat(call("DELETE", "/watchlist/" + payee, null).status()).isEqualTo(404);
        Response after = call("POST", "/screenings", transfer(txn(), account(), payee, "50.00"));
        assertThat(reasonCodes(after)).doesNotContain("R7_WATCHLIST");

        Response audit = call("GET", "/audit?entity=watchlist:" + payee, null);
        assertThat(java.util.stream.StreamSupport.stream(audit.body().get("items").spliterator(), false).map(n -> n.get("action").asText()).toList())
                .containsExactly("WATCHLIST_REMOVED", "WATCHLIST_UPDATED", "WATCHLIST_ADDED");
    }

    @Test
    void staleRequestedAtIsRejected() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        var request = transfer(txn(), account(), account(), "10.00");
        request.put("requestedAt", "2026-11-19T11:54:00+08:00");
        Response r = call("POST", "/screenings", request);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().at("/errors/0/field").asText()).isEqualTo("requestedAt");
    }

    @Test
    void scopesAreEnforced() {
        assertThat(call("GET", "/rules", null, "scopes~cases.read", Map.of()).status()).isEqualTo(403);
        assertThat(call("GET", "/rules", null, "scopes~rules.admin", Map.of()).status()).isEqualTo(200);
    }
}
