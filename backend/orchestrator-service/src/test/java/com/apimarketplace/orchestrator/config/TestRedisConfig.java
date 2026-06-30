package com.apimarketplace.orchestrator.config;

import io.lettuce.core.ClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Test configuration that provides a RedisConnectionFactory when none is auto-configured.
 * <p>
 * In {@code @DataJpaTest} sliced contexts, Redis auto-configuration is excluded,
 * but {@code RedisCacheConfig} (a {@code @Configuration} class) is still loaded and
 * requires a {@code RedisConnectionFactory} for its {@code redisTemplate()} bean.
 * <p>
 * This factory defaults to localhost:6379 but honors Spring Redis properties so
 * integration tests can point it at Testcontainers Redis.
 */
@Configuration
@Profile({"test", "e2e", "integration-test"})
public class TestRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(TestRedisConfig.class);

    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public RedisConnectionFactory redisConnectionFactory(Environment environment) {
        String host = firstNonBlank(
            environment.getProperty("spring.data.redis.host"),
            environment.getProperty("spring.redis.host"),
            "localhost");
        int port = firstConfiguredPort(environment);

        log.info("Using test RedisConnectionFactory ({}:{})", host, port);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .clientOptions(ClientOptions.builder()
                .autoReconnect(false)
                .build())
            .shutdownTimeout(Duration.ofMillis(100))
            .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
        factory.setValidateConnection(false);
        return factory;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "localhost";
    }

    private static int firstConfiguredPort(Environment environment) {
        Integer dataRedisPort = environment.getProperty("spring.data.redis.port", Integer.class);
        if (dataRedisPort != null) {
            return dataRedisPort;
        }
        Integer legacyRedisPort = environment.getProperty("spring.redis.port", Integer.class);
        return legacyRedisPort != null ? legacyRedisPort : 6379;
    }
}
