package com.apimarketplace.common.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis-backed EventBus and KeyValueStore beans.
 * Only loaded when spring-data-redis is on the classpath.
 * Falls back to in-memory implementations via {@link EventBusAutoConfiguration}.
 */
@AutoConfiguration(before = EventBusAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisEventBusConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisMessageListenerContainer.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisMessageListenerContainer eventBusListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    @ConditionalOnBean(RedisMessageListenerContainer.class)
    public EventBus redisEventBus(StringRedisTemplate stringRedisTemplate,
                                   RedisMessageListenerContainer listenerContainer,
                                   MeterRegistry meterRegistry) {
        return new RedisEventBus(stringRedisTemplate, listenerContainer, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(KeyValueStore.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public KeyValueStore redisKeyValueStore(StringRedisTemplate stringRedisTemplate,
                                             MeterRegistry meterRegistry) {
        return new RedisKeyValueStore(stringRedisTemplate, meterRegistry);
    }
}
