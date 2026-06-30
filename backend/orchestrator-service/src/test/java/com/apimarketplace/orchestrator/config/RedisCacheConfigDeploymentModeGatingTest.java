package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Gating contract for the Redis Pub/Sub beans of {@link RedisCacheConfig}.
 *
 * <p>{@code redisMessageListenerContainer}, {@code redisEventBus} and {@code redisKeyValueStore}
 * carry {@code @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice",
 * matchIfMissing = true)}. The intent: the Redis-backed event bus / key-value store only belong in
 * the microservice topology. A standalone deployment (CE monolith, no microservice registry) must NOT
 * wire them - otherwise the orchestrator would publish onto a Redis Pub/Sub channel nobody consumes.
 *
 * <p>Because {@code matchIfMissing = true}, the beans ARE present when the property is absent (the
 * default microservice topology) and when it is explicitly {@code microservice}; they are excluded
 * ONLY when {@code deployment.mode} is set to a non-{@code microservice} value such as
 * {@code standalone}. These tests pin all three branches of that gate, with the standalone exclusion
 * being the behavior left untested previously.
 */
@DisplayName("RedisCacheConfig - deployment.mode bean gating")
class RedisCacheConfigDeploymentModeGatingTest {

    /**
     * Context with every dependency the conditional beans need, so only the {@code deployment.mode}
     * gate decides whether they are wired.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(RedisCacheConfig.class);

    @Nested
    @DisplayName("standalone (the gated-off case)")
    class Standalone {

        @Test
        @DisplayName("redisMessageListenerContainer is NOT wired when deployment.mode=standalone")
        void messageListenerContainerNotWired() {
            runner.withPropertyValues("deployment.mode=standalone")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(RedisMessageListenerContainer.class));
        }

        @Test
        @DisplayName("redisEventBus is NOT wired when deployment.mode=standalone")
        void eventBusNotWired() {
            runner.withPropertyValues("deployment.mode=standalone")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(EventBus.class));
        }

        @Test
        @DisplayName("redisKeyValueStore is NOT wired when deployment.mode=standalone")
        void keyValueStoreNotWired() {
            runner.withPropertyValues("deployment.mode=standalone")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(KeyValueStore.class));
        }
    }

    @Nested
    @DisplayName("microservice / default (the gated-on cases)")
    class Microservice {

        @Test
        @DisplayName("all three Redis beans ARE wired when deployment.mode=microservice (explicit match)")
        void allBeansWiredWhenMicroservice() {
            runner.withPropertyValues("deployment.mode=microservice")
                    .run(ctx -> assertThat(ctx)
                            .hasSingleBean(RedisMessageListenerContainer.class)
                            .hasSingleBean(EventBus.class)
                            .hasSingleBean(KeyValueStore.class));
        }

        @Test
        @DisplayName("all three Redis beans ARE wired when deployment.mode is absent (matchIfMissing=true default)")
        void allBeansWiredWhenPropertyMissing() {
            runner.run(ctx -> assertThat(ctx)
                    .hasSingleBean(RedisMessageListenerContainer.class)
                    .hasSingleBean(EventBus.class)
                    .hasSingleBean(KeyValueStore.class));
        }
    }
}
