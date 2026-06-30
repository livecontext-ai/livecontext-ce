package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.common.event.InMemoryKeyValueStore;
import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.common.scaling.lock.InMemorySemaphore;
import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.InMemoryPriorityQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link RedisExecutionQueueService}.
 *
 * <p>The service is annotated
 * {@code @ConditionalOnProperty(name = "workflow.execution-queue.backend", havingValue = "redis")}
 * with NO {@code matchIfMissing}, so the distributed Redis-backed queue must be wired ONLY in
 * the horizontal-scaling ("redis") mode and must stay OUT of the context for the default
 * single-instance JVM-local mode ("memory"), for any other value, and when the property is
 * unset. The local {@link ExecutionQueueService} owns the path in those cases; wiring both
 * would register two {@code ExecutionQueue} beans and route triggers through a Redis queue
 * that single-instance deployments do not run.
 *
 * <p>Mirrors the {@code FileDownloadControllerGatingTest} {@code ApplicationContextRunner}
 * style: no full Spring Boot context, the service is registered through an {@code @Import}
 * configuration so its {@code @ConditionalOnProperty} is evaluated, the collaborators are
 * supplied (the qualified priority queue under its expected bean name), and the assertions
 * are strictly about bean presence under different {@code workflow.execution-queue.backend}
 * values.
 */
@DisplayName("RedisExecutionQueueService - workflow.execution-queue.backend=redis bean gating")
class RedisExecutionQueueServiceBackendGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisQueueDependencies.class)
            .withUserConfiguration(RedisExecutionQueueServiceImport.class);

    @Test
    @DisplayName("backend=redis - service bean IS wired (horizontal scaling mode)")
    void beanPresentWhenBackendRedis() {
        contextRunner
                .withPropertyValues("workflow.execution-queue.backend=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisExecutionQueueService.class);
                });
    }

    @Test
    @DisplayName("backend=memory - service bean is ABSENT (single-instance local queue owns the path)")
    void beanAbsentWhenBackendMemory() {
        contextRunner
                .withPropertyValues("workflow.execution-queue.backend=memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisExecutionQueueService.class);
                });
    }

    @Test
    @DisplayName("backend=none - service bean is ABSENT (no Redis queue outside horizontal scaling)")
    void beanAbsentWhenBackendNone() {
        contextRunner
                .withPropertyValues("workflow.execution-queue.backend=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisExecutionQueueService.class);
                });
    }

    @Test
    @DisplayName("backend unset - service bean is ABSENT (no matchIfMissing default)")
    void beanAbsentWhenBackendUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RedisExecutionQueueService.class);
        });
    }

    /**
     * Supplies the service's collaborators so it can wire when the gate opens. The priority
     * queue is registered under the bean name its {@code @Qualifier} expects
     * ({@code orchestratorExecutionPriorityQueue}); the rest reuse the in-memory scaling
     * primitives also used by the behavioural unit tests, with the durable claim store mocked.
     */
    @Configuration(proxyBeanMethods = false)
    static class RedisQueueDependencies {

        @org.springframework.context.annotation.Bean
        com.apimarketplace.orchestrator.trigger.ReusableTriggerService reusableTriggerService() {
            return mock(com.apimarketplace.orchestrator.trigger.ReusableTriggerService.class);
        }

        @org.springframework.context.annotation.Bean
        com.apimarketplace.orchestrator.repository.WorkflowRunRepository workflowRunRepository() {
            return mock(com.apimarketplace.orchestrator.repository.WorkflowRunRepository.class);
        }

        @org.springframework.context.annotation.Bean
        ExecutionQueueMetrics executionQueueMetrics() {
            return new ExecutionQueueMetrics(new SimpleMeterRegistry());
        }

        @org.springframework.context.annotation.Bean(name = "orchestratorExecutionPriorityQueue")
        DistributedPriorityQueue<QueuedExecutionMessage> orchestratorExecutionPriorityQueue() {
            return new InMemoryPriorityQueue<>();
        }

        @org.springframework.context.annotation.Bean
        KeyValueStore keyValueStore() {
            return new InMemoryKeyValueStore();
        }

        @org.springframework.context.annotation.Bean
        DistributedSemaphore distributedSemaphore() {
            return new InMemorySemaphore();
        }

        @org.springframework.context.annotation.Bean
        ExecutionQueueClaimStore executionQueueClaimStore() {
            return mock(ExecutionQueueClaimStore.class);
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RedisExecutionQueueService.class)
    static class RedisExecutionQueueServiceImport {
    }
}
