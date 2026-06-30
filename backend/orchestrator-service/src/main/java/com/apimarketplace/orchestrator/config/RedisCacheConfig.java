package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.common.event.RedisEventBus;
import com.apimarketplace.common.event.RedisKeyValueStore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration for orchestrator-service.
 * Provides centralized cache management for workflow execution state.
 */
@Configuration
@ConfigurationProperties(prefix = "orchestrator.cache.redis")
public class RedisCacheConfig {

    // TTL configuration (with defaults)
    private Duration executionGraphTtl = Duration.ofMinutes(30);
    private Duration snapshotTtl = Duration.ofHours(1);
    private Duration workflowStateTtl = Duration.ofHours(24);
    private Duration lockTtl = Duration.ofMinutes(10);

    /**
     * RedisTemplate configured with JSON serialization for complex objects.
     * Uses Jackson with type information to handle polymorphic types.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializer: plain strings
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value serializer: JSON with type info
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // Getters
    public Duration getExecutionGraphTtl() {
        return executionGraphTtl;
    }

    public Duration getSnapshotTtl() {
        return snapshotTtl;
    }

    public Duration getWorkflowStateTtl() {
        return workflowStateTtl;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    /**
     * RedisMessageListenerContainer for Pub/Sub subscriptions.
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

    // Setters for configuration binding
    public void setExecutionGraphTtl(Duration executionGraphTtl) {
        this.executionGraphTtl = executionGraphTtl;
    }

    public void setSnapshotTtl(Duration snapshotTtl) {
        this.snapshotTtl = snapshotTtl;
    }

    public void setWorkflowStateTtl(Duration workflowStateTtl) {
        this.workflowStateTtl = workflowStateTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }
}
