package com.apimarketplace.agent.config;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating regression for {@link RedisConfig}'s three Redis pub/sub beans, each carrying
 * {@code @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)}.
 *
 * <p>{@code redisMessageListenerContainer}, {@code redisEventBus} and {@code redisKeyValueStore}
 * exist only for the microservice (split-pod) deployment, where agent-service talks to its siblings
 * over Redis pub/sub. In a monolith deployment those beans must be absent so the in-process
 * event bus / key-value store wins instead. Because the conditions use {@code matchIfMissing = true},
 * the contract has three distinct states that the direct-instantiation unit tests cannot prove:
 * the beans are wired when {@code deployment.mode} is microservice OR absent, and absent for any
 * other explicit value. This test pins the absence-on-non-microservice branch (the gap) and the
 * matchIfMissing default against a real (minimal) Spring context.
 *
 * <p>Collaborators are registered as mocks so the beans are constructible whenever the condition
 * matches; the assertions are purely about presence/absence, never behavior.
 */
@DisplayName("RedisConfig - deployment.mode @ConditionalOnProperty bean gating")
class RedisConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
            .withConfiguration(UserConfigurations.of(RedisConfig.class));

    @Test
    @DisplayName("deployment.mode=microservice -> all three Redis pub/sub beans ARE wired")
    void wiredWhenMicroservice() {
        runner.withPropertyValues("deployment.mode=microservice")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedisMessageListenerContainer.class);
                    assertThat(ctx).hasSingleBean(EventBus.class);
                    assertThat(ctx).hasSingleBean(KeyValueStore.class);
                });
    }

    @Test
    @DisplayName("deployment.mode absent -> beans ARE wired (matchIfMissing=true default)")
    void wiredWhenPropertyAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RedisMessageListenerContainer.class);
            assertThat(ctx).hasSingleBean(EventBus.class);
            assertThat(ctx).hasSingleBean(KeyValueStore.class);
        });
    }

    @Test
    @DisplayName("deployment.mode=monolith -> none of the three Redis pub/sub beans are wired")
    void notWiredWhenMonolith() {
        runner.withPropertyValues("deployment.mode=monolith")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RedisMessageListenerContainer.class);
                    assertThat(ctx).doesNotHaveBean(EventBus.class);
                    assertThat(ctx).doesNotHaveBean(KeyValueStore.class);
                });
    }

    @Test
    @DisplayName("deployment.mode=standalone (any non-microservice value) -> beans are NOT wired")
    void notWiredWhenStandalone() {
        runner.withPropertyValues("deployment.mode=standalone")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RedisMessageListenerContainer.class);
                    assertThat(ctx).doesNotHaveBean(EventBus.class);
                    assertThat(ctx).doesNotHaveBean(KeyValueStore.class);
                });
    }
}
