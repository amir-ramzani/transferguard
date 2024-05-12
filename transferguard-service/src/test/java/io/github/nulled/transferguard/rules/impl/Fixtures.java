package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.ScreeningContext;
import io.github.nulled.transferguard.rules.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

final class Fixtures {

    static final Instant AT = OffsetDateTime.parse("2026-11-19T01:42:10+08:00").toInstant();

    private Fixtures() {}

    static Transfer transfer(String amount) {
        return transfer(amount, AT, "dev-1");
    }

    static Transfer transfer(String amount, Instant at, String deviceId) {
        return new Transfer("TXN-1", "1402-0012-3456", "1402-0098-7654", new BigDecimal(amount), deviceId, at);
    }

    static ScreeningContext ctx(Transfer t, Features f) {
        return new ScreeningContext(t, f, t.requestedAt());
    }

    static ScreeningContext ctx(String amount, Features f) {
        return ctx(transfer(amount), f);
    }
}
