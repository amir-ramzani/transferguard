package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.profile.AccountProfileService;
import io.github.nulled.transferguard.rules.Feature;
import io.github.nulled.transferguard.rules.FeatureNeeds;
import io.github.nulled.transferguard.rules.Features;
import io.github.nulled.transferguard.rules.Features.AmountHistory;
import io.github.nulled.transferguard.rules.Features.InboundCredit;
import io.github.nulled.transferguard.rules.Features.SecurityState;
import io.github.nulled.transferguard.rules.Transfer;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads every feature in one network round trip: {@code screen_features.lua} for the source account,
 * pipelined with the watchlist lookup. The script also records this attempt for R1.
 */
@Component
public class RedisFeatureLoader {

    private static final Logger log = LoggerFactory.getLogger(RedisFeatureLoader.class);

    static final Duration MIN_EVENT_RETENTION = Duration.ofMinutes(60);
    static final Duration OUT_KEY_TTL = Duration.ofHours(24);
    static final Duration AVG_TTL = Duration.ofMinutes(5);

    private final RedisGateway redis;
    private final AccountProfileService profile;
    private final String script;
    private final String sha;

    public RedisFeatureLoader(RedisGateway redis, AccountProfileService profile) throws IOException {
        this.redis = redis;
        this.profile = profile;
        try (InputStream in = new ClassPathResource("redis/screen_features.lua").getInputStream()) {
            this.script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        this.sha = io.github.nulled.transferguard.common.Sha1.hex(script);
    }

    public Features load(Transfer t, FeatureNeeds needs, Instant now) {
        String acct = t.sourceAccount();
        String[] keys = {Keys.out(acct), Keys.outDone(acct), Keys.in(acct), Keys.payees(acct), Keys.security(acct),
                Keys.average(acct)};
        long nowMs = now.toEpochMilli();
        Duration outRetention = max(needs.velocityWindow(), MIN_EVENT_RETENTION);
        Duration passWindow = needs.needs(Feature.PASS_THROUGH) ? needs.passThroughWindow() : null;
        String[] args = {
                Long.toString(nowMs),
                t.transactionId(),
                Long.toString(nowMs - outRetention.toMillis()),
                Long.toString(OUT_KEY_TTL.toMillis()),
                needs.needs(Feature.OUTBOUND_VELOCITY) ? Long.toString(nowMs - needs.velocityWindow().toMillis()) : "",
                passWindow != null ? Long.toString(nowMs - passWindow.toMillis()) : "",
                passWindow != null ? Long.toString(nowMs - max(passWindow, MIN_EVENT_RETENTION).toMillis()) : "",
                needs.needs(Feature.KNOWN_PAYEE) ? t.payeeAccount() : "",
                needs.needs(Feature.SECURITY) ? "1" : "0",
                needs.needs(Feature.AMOUNT_HISTORY) ? "1" : "0"
        };
        boolean wantWatchlist = needs.needs(Feature.WATCHLIST);

        record Raw(List<Object> script, Boolean watchlisted) {}
        Raw raw = redis.call(c -> {
            CompletionStage<List<Object>> s = runScript(c, keys, args);
            CompletionStage<Boolean> w = wantWatchlist
                    ? c.sismember(Keys.WATCHLIST, t.payeeAccount())
                    : CompletableFuture.completedFuture(Boolean.FALSE);
            return s.thenCombine(w, Raw::new);
        });
        return parse(raw.script(), raw.watchlisted(), needs, t, now);
    }

    private CompletionStage<List<Object>> runScript(RedisAsyncCommands<String, String> c, String[] keys, String[] args) {
        return c.<List<Object>>evalsha(sha, ScriptOutputType.MULTI, keys, args)
                .toCompletableFuture()
                .exceptionallyCompose(e -> {
                    Throwable cause = e instanceof java.util.concurrent.CompletionException ? e.getCause() : e;
                    if (cause instanceof RedisNoScriptException) {
                        return c.<List<Object>>eval(script, ScriptOutputType.MULTI, keys, args).toCompletableFuture();
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    private Features parse(List<Object> r, Boolean watchlisted, FeatureNeeds needs, Transfer t, Instant now) {
        Features.Builder b = Features.builder();
        int i = 0;
        String velocity = str(r.get(i++));
        if (needs.needs(Feature.OUTBOUND_VELOCITY)) {
            b.velocityCount(Integer.parseInt(velocity));
        }
        int nIn = Integer.parseInt(str(r.get(i++)));
        List<InboundCredit> credits = new ArrayList<>(nIn);
        for (int k = 0; k < nIn; k++) {
            credits.add(parseCredit(str(r.get(i++))));
        }
        int nOut = Integer.parseInt(str(r.get(i++)));
        List<BigDecimal> debits = new ArrayList<>(nOut);
        for (int k = 0; k < nOut; k++) {
            String m = str(r.get(i++));
            debits.add(new BigDecimal(m.substring(m.lastIndexOf('|') + 1)));
        }
        if (needs.needs(Feature.PASS_THROUGH)) {
            b.passThrough(credits, debits);
        }
        String payeeKnown = str(r.get(i++));
        if (needs.needs(Feature.KNOWN_PAYEE)) {
            b.payeeKnown("1".equals(payeeKnown));
        }
        String deviceId = str(r.get(i++));
        String deviceChangedAt = str(r.get(i++));
        String pwdResetAt = str(r.get(i++));
        if (needs.needs(Feature.SECURITY)) {
            b.security(new SecurityState(blankToNull(deviceId), instant(deviceChangedAt), instant(pwdResetAt)));
        }
        String avg = str(r.get(i));
        if (needs.needs(Feature.AMOUNT_HISTORY)) {
            b.history(avg.isEmpty() ? fillAverage(t.sourceAccount(), now) : parseAverage(avg));
        }
        if (needs.needs(Feature.WATCHLIST)) {
            b.payeeWatchlisted(Boolean.TRUE.equals(watchlisted));
        }
        return b.build();
    }

    /** Cache miss: read account_daily_stats once and cache "avg|count" for five minutes. */
    private AmountHistory fillAverage(String account, Instant now) {
        AmountHistory history = profile.history(account, now);
        try {
            redis.call(c -> c.set(Keys.average(account), history.average().toPlainString() + "|" + history.count(),
                    SetArgs.Builder.px(AVG_TTL.toMillis())));
        } catch (RedisUnavailableException e) {
            log.debug("Could not cache average: {}", e.getMessage());
        }
        return history;
    }

    static AmountHistory parseAverage(String value) {
        int bar = value.indexOf('|');
        return new AmountHistory(new BigDecimal(value.substring(0, bar)), Long.parseLong(value.substring(bar + 1)));
    }

    /** member = creditId|sender|amount; the sender may not contain '|' at either end, so split from both ends. */
    static InboundCredit parseCredit(String member) {
        int first = member.indexOf('|');
        int last = member.lastIndexOf('|');
        return new InboundCredit(member.substring(0, first), member.substring(first + 1, last),
                new BigDecimal(member.substring(last + 1)));
    }

    private static String str(Object o) {
        if (o == null) {
            return "";
        }
        return o instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : o.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Instant instant(String epochMs) {
        return epochMs == null || epochMs.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(epochMs));
    }

    private static Duration max(Duration a, Duration b) {
        if (a == null) {
            return b;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }
}
