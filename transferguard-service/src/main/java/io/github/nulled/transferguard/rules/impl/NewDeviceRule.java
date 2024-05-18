package io.github.nulled.transferguard.rules.impl;

import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Finding;
import io.github.nulled.transferguard.rules.Rule;
import io.github.nulled.transferguard.rules.RuleCode;
import io.github.nulled.transferguard.rules.ScreeningContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R6 (account takeover): a device change or password reset within the window before the transfer,
 * or a request from a device other than the registered one. An account with no security history
 * (first device ever seen) does not fire.
 */
@Component
public class NewDeviceRule implements Rule<NewDeviceParams> {

    @Override
    public RuleCode code() {
        return RuleCode.R6_NEW_DEVICE;
    }

    @Override
    public Class<NewDeviceParams> paramsType() {
        return NewDeviceParams.class;
    }

    @Override
    public FeatureNeeds needs(NewDeviceParams params) {
        return FeatureNeeds.of(Feature.SECURITY).withSecurityWindow(params.window());
    }

    @Override
    public Optional<Finding> evaluate(ScreeningContext ctx, NewDeviceParams params) {
        var sec = ctx.features().security();
        Instant at = ctx.transfer().requestedAt();
        if (recent(sec.deviceChangedAt(), at, params.window())) {
            return Finding.of("New device seen " + minutes(sec.deviceChangedAt(), at) + " before this transfer");
        }
        if (recent(sec.passwordResetAt(), at, params.window())) {
            return Finding.of("Password reset " + minutes(sec.passwordResetAt(), at) + " before this transfer");
        }
        String requestDevice = ctx.transfer().deviceId();
        if (requestDevice != null && sec.deviceId() != null && !requestDevice.equals(sec.deviceId())) {
            return Finding.of("Transfer from a device not registered to this account");
        }
        return Optional.empty();
    }

    private static boolean recent(Instant event, Instant at, Duration window) {
        if (event == null) {
            return false;
        }
        Duration elapsed = Duration.between(event, at);
        return elapsed.compareTo(window) < 0;
    }

    private static String minutes(Instant event, Instant at) {
        long m = Math.max(0, Duration.between(event, at).toMinutes());
        return m == 1 ? "1 minute" : m + " minutes";
    }
}
