package com.apimarketplace.catalog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test configuration providing mock beans for external services
 * used in catalog-service integration tests.
 *
 * <p>Mocks the following external dependencies:
 * <ul>
 *   <li>Redis (ReactiveRedisTemplate, RedisTemplate, connection factories)</li>
 * </ul>
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    @Primary
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        return mock(ReactiveRedisConnectionFactory.class);
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate() {
        ReactiveRedisTemplate<String, String> template = mock(ReactiveRedisTemplate.class);
        var opsForValue = mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(opsForValue);
        when(opsForValue.get(anyString())).thenReturn(Mono.empty());
        when(opsForValue.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(template.delete(anyString())).thenReturn(Mono.just(1L));
        when(template.keys(anyString())).thenReturn(reactor.core.publisher.Flux.empty());
        return template;
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        var opsForValue = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(template.opsForValue()).thenReturn(opsForValue);
        when(opsForValue.get(anyString())).thenReturn(null);
        return template;
    }
}
