package com.apimarketplace.catalog.config;

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
 * Redis configuration for catalog-service.
 * Uses Spring Boot auto-configured connection factory.
 * Provides RedisTemplate for synchronous tool execution response caching,
 * plus StringRedisTemplate / EventBus / KeyValueStore for microservice mode.
 */
@Configuration
@ConfigurationProperties(prefix = "catalog.cache.redis")
public class RedisConfig {

    // TTL configuration (with defaults)
    private Duration responseCacheTtl = Duration.ofMinutes(5);
    private Duration toolSchemaTtl = Duration.ofMinutes(60);
    private Duration mcpServerTtl = Duration.ofMinutes(5);

    /**
     * RedisTemplate for synchronous operations (tool execution caching).
     * Uses JSON serialization for complex response objects.
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
    public Duration getResponseCacheTtl() {
        return responseCacheTtl;
    }

    public Duration getToolSchemaTtl() {
        return toolSchemaTtl;
    }

    public Duration getMcpServerTtl() {
        return mcpServerTtl;
    }

    // Setters for configuration binding
    public void setResponseCacheTtl(Duration responseCacheTtl) {
        this.responseCacheTtl = responseCacheTtl;
    }

    public void setToolSchemaTtl(Duration toolSchemaTtl) {
        this.toolSchemaTtl = toolSchemaTtl;
    }

    public void setMcpServerTtl(Duration mcpServerTtl) {
        this.mcpServerTtl = mcpServerTtl;
    }

    /**
     * Blocking StringRedisTemplate for EventBus and KeyValueStore.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * RedisMessageListenerContainer for Pub/Sub subscriptions (EventBus).
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
