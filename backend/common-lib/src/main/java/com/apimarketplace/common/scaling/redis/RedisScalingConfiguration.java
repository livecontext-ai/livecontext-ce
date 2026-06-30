package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.cache.DistributedBudgetCache;
import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.common.scaling.registry.RedisServiceRegistry;
import com.apimarketplace.common.scaling.timer.DistributedTimer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed implementations of distributed scaling primitives.
 *
 * <p>Only active when {@code scaling.backend=redis} is set.
 * Loaded before {@link com.apimarketplace.common.scaling.ScalingAutoConfiguration}
 * so that Redis beans take precedence over InMemory fallbacks
 * (via {@code @ConditionalOnMissingBean}).
 *
 * <p>The {@link com.apimarketplace.common.scaling.queue.DistributedPriorityQueue}
 * is NOT wired here because it requires service-specific configuration (namespace,
 * payload type). Each service that uses a queue should create its own bean.
 */
@AutoConfiguration(before = com.apimarketplace.common.scaling.ScalingAutoConfiguration.class)
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class RedisScalingConfiguration {

    @Bean
    public DistributedSemaphore redisSemaphore(StringRedisTemplate stringRedisTemplate,
                                                MeterRegistry meterRegistry) {
        return new RedisSemaphore(stringRedisTemplate, meterRegistry);
    }

    @Bean
    public DistributedTimer redisTimer(StringRedisTemplate stringRedisTemplate,
                                       MeterRegistry meterRegistry) {
        return new RedisTimer(stringRedisTemplate, meterRegistry);
    }

    @Bean
    public DistributedBudgetCache redisBudgetCache(StringRedisTemplate stringRedisTemplate,
                                                    MeterRegistry meterRegistry) {
        return new RedisBudgetCache(stringRedisTemplate, meterRegistry);
    }

    @Bean
    public RedisServiceRegistry redisServiceRegistry(StringRedisTemplate stringRedisTemplate) {
        return new RedisServiceRegistry(stringRedisTemplate, 30_000L);
    }

    @Bean
    RedisTimerShutdownHook redisTimerShutdownHook(DistributedTimer timer) {
        return new RedisTimerShutdownHook((RedisTimer) timer);
    }

    static class RedisTimerShutdownHook {
        private final RedisTimer timer;

        RedisTimerShutdownHook(RedisTimer timer) {
            this.timer = timer;
        }

        @PreDestroy
        void shutdown() {
            timer.shutdown();
        }
    }
}
