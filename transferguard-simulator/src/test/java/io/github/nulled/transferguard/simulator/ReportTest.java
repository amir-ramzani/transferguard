package io.github.nulled.transferguard.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {

    private static Observation obs(Scenario s, String decision, String... reasons) {
        return new Observation(s, s.fraud(), "id", decision, 0, List.of(reasons), 10, 201);
    }

    @Test
    void computesPrecisionAndRecallPerRule() {
        String report = Report.markdown(List.of(
                obs(Scenario.VELOCITY_BURST, "ALLOW"),
                obs(Scenario.VELOCITY_BURST, "CHALLENGE", "R1_VELOCITY"),
                obs(Scenario.NORMAL, "CHALLENGE", "R1_VELOCITY"),
                obs(Scenario.NORMAL, "ALLOW"),
                obs(Scenario.KNOWN_MULE, "HOLD", "R7_WATCHLIST")));
        assertThat(report).contains("| R1_VELOCITY | 2 | 50.0% | 50.0% |");
        assertThat(report).contains("| R7_WATCHLIST | 1 | 100.0% | 100.0% |");
        assertThat(report).contains("Decision recall (fraud not allowed): 66.7%");
        assertThat(report).contains("false-positive rate (legitimate not allowed): 50.0%");
    }

    @Test
    void deterministicAccountsMatchTheApiPattern() {
        String pattern = "^[0-9]{4}-[0-9]{4}-[0-9]{4,8}$";
        assertThat(Accounts.customer(7)).matches(pattern).isEqualTo("1402-1000-000007");
        assertThat(Accounts.payee(7, 3)).matches(pattern).isEqualTo("1402-5003-000007");
        assertThat(Accounts.fresh("1402")).matches(pattern);
        assertThat(Accounts.TAKEOVER_VICTIM).matches(pattern);
        assertThat(Accounts.KNOWN_MULE).matches(pattern);
    }
}
