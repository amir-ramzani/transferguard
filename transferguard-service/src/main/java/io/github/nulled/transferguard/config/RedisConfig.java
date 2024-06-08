package io.github.nulled.transferguard.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce used directly: a 50 ms command timeout on the screening path, commands rejected (not
 * queued) while disconnected, and a separate client with a longer timeout for bulk rebuilds.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public ClientResources redisClientResources() {
        return DefaultClientResources.create();
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient fastRedisClient(ClientResources resources, TransferGuardProperties props) {
        return client(resources, props, props.redis().commandTimeout());
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient bulkRedisClient(ClientResources resources, TransferGuardProperties props) {
        return client(resources, props, props.redis().bulkTimeout());
    }

    private static RedisClient client(ClientResources resources, TransferGuardProperties props, Duration timeout) {
        RedisURI uri = RedisURI.create(props.redis().url());
        uri.setTimeout(timeout);
        RedisClient client = RedisClient.create(resources, uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .socketOptions(SocketOptions.builder().connectTimeout(props.redis().connectTimeout()).build())
                .build());
        return client;
    }
}
