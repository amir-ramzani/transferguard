package io.github.nulled.transferguard.rules;

import java.util.function.Consumer;

/**
 * Tells every pod that a rule or setting changed (Redis channel {@code tg:rules:changed}). Defined
 * here and implemented in {@code features}, so the rules package never depends on Redis.
 */
public interface RuleChangeBus {

    void publish(String change);

    void subscribe(Consumer<String> listener);
}
