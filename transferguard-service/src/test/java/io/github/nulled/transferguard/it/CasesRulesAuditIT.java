package io.github.nulled.transferguard.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class CasesRulesAuditIT extends IntegrationTest {

    private String holdCase(String payee) {
        // Watchlisted payee → R7 (70) → HOLD → case.
        call("PUT", "/watchlist/" + payee, Map.of("source", "MANUAL", "reason", "setup"));
        Response r = call("POST", "/screenings", transfer(txn(), account(), payee, "60.00"));
        call("DELETE", "/watchlist/" + payee, null);
        assertThat(r.body().get("decision").asString()).isEqualTo("HOLD");
        return r.body().get("caseId").asString();
    }

    @Test
    void analystAssignsAndResolvesACaseAndTheChainVerifies() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String payee = account();
        String caseId = holdCase(payee);

        Response open = call("GET", "/cases?status=OPEN&size=100", null);
        assertThat(open.body().get("items").valueStream().map(n -> n.get("caseId").asString())).contains(caseId);

        Response assigned = call("PATCH", "/cases/" + caseId, Map.of("assignee", "analyst-it"));
        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.body().get("assignee").asString()).isEqualTo("analyst-it");

        assertThat(call("PATCH", "/cases/" + caseId, Map.of("status", "CONFIRMED_FRAUD")).status())
                .as("resolutionNote required").isEqualTo(400);

        Response resolved = call("PATCH", "/cases/" + caseId, Map.of("status", "CONFIRMED_FRAUD",
                "resolutionNote", "Customer confirmed a scam call", "addPayeeToWatchlist", true));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asString()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(resolved.body().get("resolvedAt").isNull()).isFalse();

        assertThat(call("PATCH", "/cases/" + caseId, Map.of("assignee", "someone")).status())
                .as("resolution is final").isEqualTo(409);
        assertThat(call("POST", "/cases/" + caseId + "/notes", Map.of("note", "Correction: two calls")).status())
                .isEqualTo(201);

        Response detail = call("GET", "/cases/" + caseId, null);
        assertThat(detail.body().get("notes").size()).isEqualTo(2);
        assertThat(detail.body().at("/screening/request/payeeAccount").asString()).isEqualTo(payee);

        Response watch = call("GET", "/watchlist?size=100", null);
        assertThat(watch.body().get("items").valueStream().map(n -> n.get("accountNo").asString())).contains(payee);

        Response audit = call("GET", "/audit?entity=case:" + caseId, null);
        assertThat(audit.body().get("items").valueStream().map(n -> n.get("action").asString()).toList())
                .containsExactly("CASE_NOTE_ADDED", "CASE_RESOLVED", "CASE_ASSIGNED");

        Response verify = call("GET", "/audit/verify", null);
        assertThat(verify.body().get("valid").asBoolean()).isTrue();
    }

    @Test
    void editingAnAuditRowBreaksTheChainAndTheAppRoleCannotDoIt() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        String payee = account();
        call("PUT", "/watchlist/" + payee, Map.of("source", "MANUAL", "reason", "tamper test"));
        long id = call("GET", "/audit?entity=watchlist:" + payee, null).body().at("/items/0/id").asLong();

        assertThatThrownBy(() -> appJdbc.update("UPDATE audit_log SET actor = 'mallory' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);

        var su = superuser();
        String actor = su.queryForObject("SELECT actor FROM audit_log WHERE id = ?", String.class, id);
        su.update("UPDATE audit_log SET actor = 'mallory' WHERE id = ?", id);
        try {
            Response broken = call("GET", "/audit/verify", null);
            assertThat(broken.body().get("valid").asBoolean()).isFalse();
            assertThat(broken.body().get("firstBrokenId").asLong()).isEqualTo(id);
        } finally {
            su.update("UPDATE audit_log SET actor = ? WHERE id = ?", actor, id);
        }
        assertThat(call("GET", "/audit/verify", null).body().get("valid").asBoolean()).isTrue();
    }

    @Test
    void ruleTuningNeedsIfMatchAndTakesEffectAtOnce() {
        CLOCK.set("2026-11-19T12:00:00+08:00");
        Response rule = call("GET", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT", null);
        String etag = rule.header("ETag");
        var update = Map.of("enabled", true, "weight", 25, "params", Map.of("minAmount", "100.00"));

        assertThat(call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT", update).status()).isEqualTo(428);
        assertThat(call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT", update, ALL, Map.of("If-Match", "\"999\"")).status())
                .isEqualTo(412);
        assertThat(call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT",
                Map.of("enabled", true, "weight", 25, "params", Map.of("minAmount", "100.00", "x", 1)), ALL,
                Map.of("If-Match", etag)).status()).isEqualTo(400);

        Response updated = call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT", update, ALL, Map.of("If-Match", etag));
        try {
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.header("ETag")).isNotEqualTo(etag);
            Response screened = call("POST", "/screenings", transfer(txn(), account(), account(), "150.00"));
            assertThat(reasonCodes(screened)).contains("R2_NEW_PAYEE_HIGH_AMOUNT");
            assertThat(call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT", update, ALL, Map.of("If-Match", etag)).status())
                    .as("stale ETag").isEqualTo(412);
        } finally {
            call("PUT", "/rules/R2_NEW_PAYEE_HIGH_AMOUNT",
                    Map.of("enabled", true, "weight", 25, "params", Map.of("minAmount", "3000.00")), ALL,
                    Map.of("If-Match", updated.header("ETag")));
        }
        Response audit = call("GET", "/audit?entity=rule:R2_NEW_PAYEE_HIGH_AMOUNT", null);
        assertThat(audit.body().at("/items/0/action").asString()).isEqualTo("RULE_UPDATED");
    }

    @Test
    void engineSettingsRoundTrip() {
        Response s = call("GET", "/settings/engine", null);
        assertThat(s.body().get("allowMax").asInt()).isEqualTo(39);
        var bad = Map.of("allowMax", 70, "challengeMax", 69, "failMode", "DEGRADED");
        assertThat(call("PUT", "/settings/engine", bad, ALL, Map.of("If-Match", s.header("ETag"))).status())
                .isEqualTo(400);
        var same = Map.of("allowMax", 39, "challengeMax", 69, "failMode", "DEGRADED",
                "degradedChallengeMinAmount", "1000.00");
        Response ok = call("PUT", "/settings/engine", same, ALL, Map.of("If-Match", s.header("ETag")));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.header("ETag")).isNotEqualTo(s.header("ETag"));
    }
}
