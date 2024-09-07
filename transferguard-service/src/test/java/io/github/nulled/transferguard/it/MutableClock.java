package io.github.nulled.transferguard.it;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can set and advance, so windows (R1, R4, R6) can be tested exactly. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void set(String offsetDateTime) {
        now.set(OffsetDateTime.parse(offsetDateTime).toInstant());
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    /** Current time as the bank sees it, for requestedAt fields. */
    public String local() {
        return now.get().atOffset(ZoneOffset.ofHours(8)).toString();
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
