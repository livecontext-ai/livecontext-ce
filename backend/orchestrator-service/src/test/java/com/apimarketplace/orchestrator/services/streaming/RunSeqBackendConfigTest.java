package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the {@link RunSeqBackend} strategy is selected by {@code scaling.backend}:
 * Redis (shared cross-pod counter) in prod, in-memory otherwise. A mis-wire here is
 * exactly what reintroduces the run-page-freeze bug on a multi-replica deployment.
 */
@DisplayName("RunSeqBackendConfig - conditional backend selection")
class RunSeqBackendConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunSeqBackendConfig.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    @DisplayName("scaling.backend=redis -> RedisRunSeqBackend (shared counter, multi-pod safe)")
    void redisBackendWhenScalingRedis() {
        runner.withPropertyValues("scaling.backend=redis").run(ctx ->
                assertThat(ctx).getBean(RunSeqBackend.class).isInstanceOf(RedisRunSeqBackend.class));
    }

    @Test
    @DisplayName("scaling.backend=memory -> InMemoryRunSeqBackend (single-pod)")
    void inMemoryBackendWhenScalingMemory() {
        runner.withPropertyValues("scaling.backend=memory").run(ctx ->
                assertThat(ctx).getBean(RunSeqBackend.class).isInstanceOf(InMemoryRunSeqBackend.class));
    }

    @Test
    @DisplayName("no scaling.backend property -> InMemoryRunSeqBackend (safe default)")
    void inMemoryBackendByDefault() {
        runner.run(ctx ->
                assertThat(ctx).getBean(RunSeqBackend.class).isInstanceOf(InMemoryRunSeqBackend.class));
    }

    @Test
    @DisplayName("exactly one RunSeqBackend bean is created (no ambiguity)")
    void exactlyOneBackend() {
        runner.withPropertyValues("scaling.backend=redis").run(ctx ->
                assertThat(ctx.getBeansOfType(RunSeqBackend.class)).hasSize(1));
    }
}
