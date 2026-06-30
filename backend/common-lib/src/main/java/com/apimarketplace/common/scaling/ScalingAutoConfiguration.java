package com.apimarketplace.common.scaling;

import com.apimarketplace.common.scaling.cache.DistributedBudgetCache;
import com.apimarketplace.common.scaling.cache.InMemoryBudgetCache;
import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.common.scaling.lock.InMemorySemaphore;
import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.InMemoryPriorityQueue;
import com.apimarketplace.common.scaling.timer.DistributedTimer;
import com.apimarketplace.common.scaling.timer.InMemoryTimer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Fallback auto-configuration for distributed scaling primitives.
 * Provides in-memory implementations when Redis/distributed backends are not available.
 * <p>
 * Redis-backed implementations will be loaded first (when configured) via a separate
 * configuration class annotated with {@code @AutoConfiguration(before = ScalingAutoConfiguration.class)}.
 */
@AutoConfiguration
public class ScalingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedPriorityQueue.class)
    public DistributedPriorityQueue<?> distributedPriorityQueue() {
        return new InMemoryPriorityQueue<>();
    }

    @Bean
    @ConditionalOnMissingBean(DistributedSemaphore.class)
    public DistributedSemaphore distributedSemaphore() {
        return new InMemorySemaphore();
    }

    @Bean
    @ConditionalOnMissingBean(DistributedTimer.class)
    public DistributedTimer distributedTimer() {
        return new InMemoryTimer();
    }

    @Bean
    @ConditionalOnMissingBean(DistributedBudgetCache.class)
    public DistributedBudgetCache distributedBudgetCache() {
        return new InMemoryBudgetCache();
    }
}
