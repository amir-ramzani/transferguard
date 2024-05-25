package io.github.nulled.transferguard.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nulled.transferguard.common.ApiException;
import org.junit.jupiter.api.Test;

class RuleParamsValidationTest {

    private final RuleConfigService config = SeedRules.configService();

    @Test
    void everySeededRuleHasValidParams() {
        assertThat(SeedRules.rows()).hasSize(RuleCode.values().length);
        assertThat(SeedRules.active()).hasSize(RuleCode.values().length);
    }

    @Test
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> parse(RuleCode.R1_VELOCITY, "{\"maxCount\":5,\"window\":\"PT10M\",\"x\":1}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void beanValidationNamesTheField() {
        assertThatThrownBy(() -> parse(RuleCode.R1_VELOCITY, "{\"maxCount\":0,\"window\":\"PT10M\"}"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.errors()).extracting(ApiException.FieldError::field).containsExactly("params.maxCount"));
    }

    @Test
    void missingFieldIsRejected() {
        assertThatThrownBy(() -> parse(RuleCode.R2_NEW_PAYEE_HIGH_AMOUNT, "{}")).isInstanceOf(ApiException.class);
    }

    @Test
    void moneyMustBeATwoDecimalString() {
        assertThatThrownBy(() -> parse(RuleCode.R2_NEW_PAYEE_HIGH_AMOUNT, "{\"minAmount\":\"3000\"}"))
                .isInstanceOf(ApiException.class);
        assertThat(parse(RuleCode.R2_NEW_PAYEE_HIGH_AMOUNT, "{\"minAmount\":\"2500.00\"}")).isNotNull();
    }

    @Test
    void crossChecksRun() {
        assertThatThrownBy(() -> parse(RuleCode.R1_VELOCITY, "{\"maxCount\":5,\"window\":\"PT0S\"}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parse(RuleCode.R4_ODD_HOURS,
                "{\"start\":\"00:00\",\"end\":\"06:00\",\"minAmount\":\"1.00\",\"zone\":\"Nowhere/Land\"}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void watchlistTakesNoParams() {
        assertThat(parse(RuleCode.R7_WATCHLIST, "{}")).isNotNull();
        assertThatThrownBy(() -> parse(RuleCode.R7_WATCHLIST, "{\"a\":1}")).isInstanceOf(ApiException.class);
    }

    @Test
    void widerVelocityWindowFromTheContractExampleIsValid() {
        assertThat(parse(RuleCode.R1_VELOCITY, "{\"maxCount\":5,\"window\":\"PT15M\"}")).isNotNull();
    }

    private RuleParams parse(RuleCode code, String json) {
        return config.parseParams(config.rule(code), SeedRules.json(json), "params.");
    }
}
