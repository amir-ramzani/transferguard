package io.github.nulled.transferguard.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Decisions and responses use the bank's local zone. */
public final class Times {

    public static final ZoneId BANK_ZONE = ZoneId.of("Asia/Kuala_Lumpur");

    private Times() {}

    public static OffsetDateTime local(Instant instant) {
        return instant == null ? null : instant.atZone(BANK_ZONE).toOffsetDateTime();
    }

    /** PostgreSQL stores microseconds; responses show milliseconds. */
    public static Instant millis(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
