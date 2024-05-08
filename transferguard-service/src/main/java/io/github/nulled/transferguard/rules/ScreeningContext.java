package io.github.nulled.transferguard.rules;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input to every rule: the transfer, its pre-loaded features and the server time. Rules may leave
 * notes (for example "R3 skipped: no history"); the engine turns them into trace attributes.
 */
public record ScreeningContext(Transfer transfer, Features features, Instant now, Map<String, String> notes) {

    public ScreeningContext(Transfer transfer, Features features, Instant now) {
        this(transfer, features, now, new LinkedHashMap<>());
    }

    public void note(String key, String value) {
        notes.put(key, value);
    }
}
