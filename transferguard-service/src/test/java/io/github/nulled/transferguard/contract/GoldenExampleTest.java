package io.github.nulled.transferguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Features.AmountHistory;
import io.github.nulled.transferguard.rules.Features.SecurityState;
import io.github.nulled.transferguard.rules.RuleEngine;
import io.github.nulled.transferguard.rules.RuleHit;
import io.github.nulled.transferguard.rules.ScreeningContext;
import io.github.nulled.transferguard.rules.SeedRules;
import io.github.nulled.transferguard.rules.Transfer;
import io.github.nulled.transferguard.screening.DecisionPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Pins the contract's example: the takeover request in docs/openapi.yaml, with the account state the
 * example implies, must produce exactly the HoldResponse example (score 80, four reasons). The full
 * HTTP version of this test is {@code ScreeningFlowIT#goldenExample}.
 */
class GoldenExampleTest {

    @Test
    void contractExampleRequestGivesTheContractExampleResponse() {
        JsonNode spec = OpenApi.load();
        JsonNode request = spec.at("/paths/~1screenings/post/requestBody/content/application~1json/examples/takeover/value");
        JsonNode expected = spec.at("/components/examples/HoldResponse/value");

        Instant requestedAt = OffsetDateTime.parse(request.get("requestedAt").asString()).toInstant();
        Transfer transfer = new Transfer(request.get("transactionId").asString(), request.get("sourceAccount").asString(),
                request.get("payeeAccount").asString(), new BigDecimal(request.get("amount").asString()),
                request.get("deviceId").asString(), requestedAt);
        // State behind the example: a new payee, a 600.00 average over 3+ transfers, a device change
        // 12 minutes earlier, nothing else.
        Features features = Features.builder()
                .velocityCount(1)
                .passThrough(List.of(), List.of())
                .payeeKnown(false)
                .security(new SecurityState("dev-7f3a", requestedAt.minus(Duration.ofMinutes(12)), null))
                .history(new AmountHistory(new BigDecimal("600.00"), 3))
                .payeeWatchlisted(false)
                .build();

        var eval = new RuleEngine().evaluate(SeedRules.active(), new ScreeningContext(transfer, features, requestedAt));
        var decision = new DecisionPolicy().decide(eval.score(), false, transfer.amount(), SeedRules.settings());

        assertThat(eval.score()).isEqualTo(expected.get("score").asInt());
        assertThat(decision.name()).isEqualTo(expected.get("decision").asString());
        List<RuleHit> expectedReasons = expected.get("reasons").valueStream()
                .map(r -> new RuleHit(r.get("code").asString(), r.get("points").asInt(), r.get("message").asString()))
                .toList();
        assertThat(eval.hits()).containsExactlyElementsOf(expectedReasons);
    }
}
