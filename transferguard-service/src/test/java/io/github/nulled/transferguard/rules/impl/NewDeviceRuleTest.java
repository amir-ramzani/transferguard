package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.AT;
import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static io.github.nulled.transferguard.rules.impl.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Features.SecurityState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NewDeviceRuleTest {

    private final NewDeviceRule rule = new NewDeviceRule();
    private final NewDeviceParams params = new NewDeviceParams(Duration.ofMinutes(30));

    private static Features sec(SecurityState s) {
        return Features.builder().security(s).build();
    }

    @Test
    void firstDeviceEverSeenDoesNotFire() {
        assertThat(rule.evaluate(ctx(transfer("4800.00", AT, "dev-new"), sec(SecurityState.NONE)), params)).isEmpty();
    }

    @Test
    void deviceChangeTwelveMinutesBeforeFires() {
        var s = new SecurityState("dev-7f3a", AT.minus(Duration.ofMinutes(12)), null);
        assertThat(rule.evaluate(ctx(transfer("4800.00", AT, "dev-7f3a"), sec(s)), params)).get()
                .extracting("message").isEqualTo("New device seen 12 minutes before this transfer");
    }

    @Test
    void passwordResetThirtyOneMinutesAgoDoesNotFire() {
        var s = new SecurityState(null, null, AT.minus(Duration.ofMinutes(31)));
        assertThat(rule.evaluate(ctx(transfer("4800.00", AT, null), sec(s)), params)).isEmpty();
    }

    @Test
    void passwordResetAtTheWindowEdgeDoesNotFire() {
        var s = new SecurityState(null, null, AT.minus(Duration.ofMinutes(30)));
        assertThat(rule.evaluate(ctx(transfer("4800.00", AT, null), sec(s)), params)).isEmpty();
    }

    @Test
    void recentPasswordResetFires() {
        var s = new SecurityState(null, null, AT.minus(Duration.ofMinutes(1)));
        assertThat(rule.evaluate(ctx(transfer("4800.00", AT, null), sec(s)), params)).get()
                .extracting("message").isEqualTo("Password reset 1 minute before this transfer");
    }

    @Test
    void unregisteredDeviceFires() {
        var s = new SecurityState("dev-old", AT.minus(Duration.ofDays(3)), null);
        assertThat(rule.evaluate(ctx(transfer("50.00", AT, "dev-other"), sec(s)), params)).get()
                .extracting("message").isEqualTo("Transfer from a device not registered to this account");
    }

    @Test
    void registeredDeviceDoesNotFire() {
        var s = new SecurityState("dev-old", AT.minus(Duration.ofDays(3)), null);
        assertThat(rule.evaluate(ctx(transfer("50.00", AT, "dev-old"), sec(s)), params)).isEmpty();
        assertThat(rule.evaluate(ctx(transfer("50.00", AT, null), sec(s)), params)).isEmpty();
    }
}
