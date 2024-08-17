package io.github.nulled.transferguard.features;

import io.github.nulled.transferguard.rules.RuleChangeBus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub on {@code tg:rules:changed}. If Redis is down at startup the subscriber keeps
 * retrying; Lettuce resubscribes by itself after a reconnect.
 */
@Component
public class RedisRuleChangeBus implements RuleChangeBus {

    private static final Logger log = LoggerFactory.getLogger(RedisRuleChangeBus.class);
    private static final Duration RETRY = Duration.ofSeconds(10);

    private final RedisGateway redis;
    private final RedisClient client;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile StatefulRedisPubSubConnection<String, String> connection;
    private volatile boolean stopped;

    public RedisRuleChangeBus(RedisGateway redis, @Qualifier("bulkRedisClient") RedisClient client) {
        this.redis = redis;
        this.client = client;
    }

    @Override
    public void publish(String change) {
        redis.call(c -> c.publish(Keys.RULES_CHANGED, change));
    }

    @Override
    public void subscribe(Consumer<String> listener) {
        listeners.add(listener);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("rules-changed-subscriber").start(() -> {
            while (!stopped && connection == null) {
                try {
                    StatefulRedisPubSubConnection<String, String> c = client.connectPubSub();
                    c.addListener(new RedisPubSubAdapter<>() {
                        @Override
                        public void message(String channel, String message) {
                            listeners.forEach(l -> l.accept(message));
                        }
                    });
                    c.sync().subscribe(Keys.RULES_CHANGED);
                    connection = c;
                    log.info("Subscribed to {}", Keys.RULES_CHANGED);
                } catch (RuntimeException e) {
                    log.warn("Cannot subscribe to {} yet ({}); retrying in {}s", Keys.RULES_CHANGED, e.getMessage(),
                            RETRY.toSeconds());
                    try {
                        Thread.sleep(RETRY);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    @PreDestroy
    void stop() {
        stopped = true;
        if (connection != null) {
            connection.close();
        }
    }
}
