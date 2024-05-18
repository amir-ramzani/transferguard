package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R4: a larger transfer requested in [start, end) local time. The window may wrap midnight
 * (for example 23:00 to 05:00). Uses the validated {@code requestedAt}, converted to the rule's zone.
 */
@Component
public class OddHoursRule implements Rule<OddHoursParams> {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public RuleCode code() {
        return RuleCode.R4_ODD_HOURS;
    }

    @Override
    public Class<OddHoursParams> paramsType() {
        return OddHoursParams.class;
    }

    @Override
    public FeatureNeeds needs(OddHoursParams params) {
        return FeatureNeeds.NONE;
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, OddHoursParams params) {
        if (ctx.transfer().amount().compareTo(params.minAmountValue()) < 0) {
            return Optional.empty();
        }
        LocalTime local = ctx.transfer().requestedAt().atZone(params.zoneId()).toLocalTime();
        if (inWindow(local, params.startTime(), params.endTime())) {
            return Finding.of("Transfer requested at " + HH_MM.format(local));
        }
        return Optional.empty();
    }

    static boolean inWindow(LocalTime t, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }
}
