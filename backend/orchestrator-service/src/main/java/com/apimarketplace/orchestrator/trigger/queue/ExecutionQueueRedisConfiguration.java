package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.PriorityTierWeights;
import com.apimarketplace.common.scaling.redis.RedisPriorityQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
class ExecutionQueueRedisConfiguration {

    @Bean(name = "orchestratorExecutionPriorityQueue")
    @ConditionalOnProperty(name = "workflow.execution-queue.backend", havingValue = "redis")
    DistributedPriorityQueue<QueuedExecutionMessage> orchestratorExecutionPriorityQueue(
            StringRedisTemplate stringRedisTemplate,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            @Value("${workflow.execution-queue.redis.namespace:orch:queue:exec}") String namespace,
            @Value("${workflow.execution-queue.redis.reclaim-idle-ms:60000}") long reclaimIdleMs) {

        return new RedisPriorityQueue<>(
                stringRedisTemplate,
                meterRegistry,
                objectMapper,
                namespace,
                new PriorityTierWeights(),
                QueuedExecutionMessage.class,
                Duration.ofMillis(reclaimIdleMs));
    }
}
