package io.github.nulled.transferguard.rules.impl;

import java.time.Duration;

final class Durations {

    static final Duration MAX_REALTIME_WINDOW = Duration.ofHours(24);

    private Durations() {}

    /** "10 minutes", "1 hour", "90 minutes". */
    static String human(Duration d) {
        long minutes = d.toMinutes();
        if (minutes > 0 && minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "hour" : hours + " hours";
        }
        if (minutes >= 1) {
            return minutes == 1 ? "minute" : minutes + " minutes";
        }
        long seconds = d.toSeconds();
        return seconds == 1 ? "second" : seconds + " seconds";
    }

    static String checkWindow(String name, Duration window, Duration max) {
        if (window == null || window.isNegative() || window.isZero()) {
            return name + " must be a positive ISO-8601 duration";
        }
        if (window.compareTo(max) > 0) {
            return name + " must not exceed " + max;
        }
        return null;
    }
}
