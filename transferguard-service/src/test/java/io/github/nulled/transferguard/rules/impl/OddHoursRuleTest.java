package io.github.nulled.transferguard.rules.impl;

import static io.github.nulled.transferguard.rules.impl.Fixtures.ctx;
import static io.github.nulled.transferguard.rules.impl.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.rules.Features;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OddHoursRuleTest {

    private final OddHoursRule rule = new OddHoursRule();
    private final OddHoursParams params = new OddHoursParams("00:00", "06:00", "1000.00", "Asia/Kuala_Lumpur");
    private final Features none = Features.builder().build();

    private static Instant kl(String time) {
        return OffsetDateTime.parse("2026-11-19T" + time + "+08:00").toInstant();
    }

    @Test
    void lastSecondOfTheWindowFires() {
        assertThat(rule.evaluate(ctx(transfer("1000.00", kl("05:59:59"), null), none), params))
                .get().extracting("message").isEqualTo("Transfer requested at 05:59");
    }

    @Test
    void endOfTheWindowDoesNotFire() {
        assertThat(rule.evaluate(ctx(transfer("1000.00", kl("06:00:00"), null), none), params)).isEmpty();
    }

    @Test
    void startOfTheWindowFires() {
        assertThat(rule.evaluate(ctx(transfer("5000.00", kl("00:00:00"), null), none), params)).isPresent();
    }

    @Test
    void utcTimestampIsConvertedToTheRuleZone() {
        Instant utc = Instant.parse("2026-11-18T17:42:10Z"); // 01:42 in Kuala Lumpur
        assertThat(rule.evaluate(ctx(transfer("4800.00", utc, null), none), params))
                .get().extracting("message").isEqualTo("Transfer requested at 01:42");
    }

    @Test
    void smallAmountDoesNotFire() {
        assertThat(rule.evaluate(ctx(transfer("999.99", kl("02:00:00"), null), none), params)).isEmpty();
    }

    @Test
    void windowMayWrapMidnight() {
        assertThat(OddHoursRule.inWindow(LocalTime.of(23, 30), LocalTime.of(23, 0), LocalTime.of(2, 0))).isTrue();
        assertThat(OddHoursRule.inWindow(LocalTime.of(1, 59), LocalTime.of(23, 0), LocalTime.of(2, 0))).isTrue();
        assertThat(OddHoursRule.inWindow(LocalTime.of(2, 0), LocalTime.of(23, 0), LocalTime.of(2, 0))).isFalse();
        assertThat(OddHoursRule.inWindow(LocalTime.of(22, 59), LocalTime.of(23, 0), LocalTime.of(2, 0))).isFalse();
    }

    @Test
    void crossChecksZoneAndWindow() {
        assertThat(new OddHoursParams("00:00", "06:00", "1000.00", "Mars/Olympus").crossCheck()).contains("zone");
        assertThat(new OddHoursParams("03:00", "03:00", "1000.00", "UTC").crossCheck()).contains("differ");
        assertThat(params.crossCheck()).isNull();
    }
}
