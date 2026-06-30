package com.apimarketplace.conversation.config;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.common.event.RedisEventBus;
import com.apimarketplace.common.event.RedisKeyValueStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for reactive stream state management.
 * Uses Spring Boot auto-configured connection factory.
 * Provides ReactiveRedisTemplate and Pub/Sub listener for distributed streaming.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        RedisSerializationContext<String, String> serializationContext =
                RedisSerializationContext.<String, String>newSerializationContext(new StringRedisSerializer())
                        .key(new StringRedisSerializer())
                        .value(new StringRedisSerializer())
                        .hashKey(new StringRedisSerializer())
                        .hashValue(new StringRedisSerializer())
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

    @Bean
    public ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    /**
     * Blocking StringRedisTemplate for EventBus and KeyValueStore.
     * The reactive starter auto-configures LettuceConnectionFactory which implements
     * both ReactiveRedisConnectionFactory and RedisConnectionFactory.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Blocking RedisMessageListenerContainer for Pub/Sub subscriptions (EventBus).
     */
    @Bean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * Redis-backed EventBus for microservice mode.
     */
    @Bean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public EventBus redisEventBus(StringRedisTemplate stringRedisTemplate,
                                   RedisMessageListenerContainer listenerContainer,
                                   io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new RedisEventBus(stringRedisTemplate, listenerContainer, meterRegistry);
    }

    /**
     * Redis-backed KeyValueStore for microservice mode.
     */
    @Bean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public KeyValueStore redisKeyValueStore(StringRedisTemplate stringRedisTemplate,
                                            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new RedisKeyValueStore(stringRedisTemplate, meterRegistry);
    }
}
