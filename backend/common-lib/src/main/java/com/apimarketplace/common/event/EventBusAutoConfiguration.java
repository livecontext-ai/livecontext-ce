package com.apimarketplace.common.event;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Fallback auto-configuration for EventBus and KeyValueStore.
 * Provides in-memory implementations when Redis is not available.
 * <p>
 * Redis-backed implementations are in {@link RedisEventBusConfiguration},
 * which is loaded first (when spring-data-redis is on the classpath).
 */
@AutoConfiguration(after = RedisEventBusConfiguration.class)
public class EventBusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus fallbackEventBus() {
        return new InMemoryEventBus();
    }

    @Bean
    @ConditionalOnMissingBean(KeyValueStore.class)
    public KeyValueStore fallbackKeyValueStore() {
        return new InMemoryKeyValueStore();
    }
}
