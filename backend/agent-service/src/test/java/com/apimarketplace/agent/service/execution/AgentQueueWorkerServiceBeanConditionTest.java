package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.config.AgentScalingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link AgentQueueWorkerService}.
 *
 * <p>The worker bean carries
 * {@code @ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")}
 * with no {@code matchIfMissing}, so it must be wired ONLY when the queue is explicitly
 * enabled. On the default deployment (property unset) and when explicitly disabled the bean
 * must be ABSENT, otherwise the three worker thread pools would spin up and BRPOP a Redis
 * queue that nothing is feeding. This guards the bean's presence/absence, which the existing
 * {@code AgentQueueWorkerServiceTest} (which constructs the service directly) never exercises.
 */
@DisplayName("AgentQueueWorkerService bean gating (scaling.agent.queue.enabled)")
class AgentQueueWorkerServiceBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AgentScalingConfig.class, AgentScalingConfig::new)
            .withBean(AgentRemoteExecutionService.class, () -> mock(AgentRemoteExecutionService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ActiveStreamRegistry.class, () -> mock(ActiveStreamRegistry.class))
            .withUserConfiguration(AgentQueueWorkerService.class);

    @Test
    @DisplayName("Bean is NOT wired when scaling.agent.queue.enabled is unset (default deployment)")
    void beanAbsentWhenPropertyUnset() {
        runner.run(ctx -> assertThat(ctx)
                .as("default deployment must not start queue workers")
                .doesNotHaveBean(AgentQueueWorkerService.class));
    }

    @Test
    @DisplayName("Bean is NOT wired when scaling.agent.queue.enabled=false")
    void beanAbsentWhenPropertyFalse() {
        runner.withPropertyValues("scaling.agent.queue.enabled=false")
                .run(ctx -> assertThat(ctx)
                        .as("explicitly disabled queue must not start workers")
                        .doesNotHaveBean(AgentQueueWorkerService.class));
    }

    @Test
    @DisplayName("Bean IS wired when scaling.agent.queue.enabled=true")
    void beanPresentWhenPropertyTrue() {
        runner.withPropertyValues("scaling.agent.queue.enabled=true")
                .run(ctx -> assertThat(ctx)
                        .as("queue workers must be wired only when explicitly enabled")
                        .hasSingleBean(AgentQueueWorkerService.class));
    }
}
