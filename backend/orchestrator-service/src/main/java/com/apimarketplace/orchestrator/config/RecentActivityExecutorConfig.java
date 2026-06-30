package com.apimarketplace.orchestrator.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated bounded thread pool for the recent-activity fan-out
 * ({@code RecentActivityAggregatorService}). Isolated from
 * {@code ForkJoinPool.commonPool} which is already contended by
 * {@code UnifiedExecutionEngine}, {@code SplitAwareNodeExecutor},
 * {@code ReusableTriggerService}, and {@code RunCoalescingService} (auditor B
 * v6 fix). On a 4-vCPU box, commonPool parallelism=3 - sharing it with the
 * recent-activity fan-out would make it a noisy neighbour to workflow
 * execution.
 *
 * <p>Sizing rationale: 4-way fan-out + ~3s p99 per branch × ~100 req/s
 * steady-state with ~50% cache hit rate ⇒ ~200 task submissions/s × 3s
 * in-flight ≈ 600 concurrent peak. Pool core=8 max=16 queue=64 +
 * {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy}: when
 * the queue saturates, the calling Tomcat thread executes the fan-out
 * branch inline (back-pressure on the request, not 503 rejection). At
 * sustained saturation a few Tomcat workers will be parked for up to 3s
 * per branch - surface via the queue-size gauge below before users feel
 * it. Mirrors the bounded-executor + caller-runs pattern used elsewhere
 * in this service for the same reason.
 */
@Configuration
public class RecentActivityExecutorConfig {

    public static final String EXECUTOR_BEAN_NAME = "recentActivityExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolExecutor recentActivityExecutor(@Autowired(required = false) MeterRegistry meterRegistry) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8,                                  // core
                16,                                 // max
                60L, TimeUnit.SECONDS,              // keep-alive
                new ArrayBlockingQueue<>(64),       // bounded queue
                namedThreadFactory("recent-activity-"),
                new ThreadPoolExecutor.CallerRunsPolicy());

        if (meterRegistry != null) {
            // Surface saturation before users see latency tails. Track queue
            // depth + active threads. Hit/miss + per-branch latency are
            // recorded inside RecentActivityAggregatorService itself.
            Gauge.builder("recent_activity_executor.queue.size", executor, e -> e.getQueue().size())
                    .description("Tasks queued in the recent-activity bounded executor")
                    .register(meterRegistry);
            Gauge.builder("recent_activity_executor.active", executor, ThreadPoolExecutor::getActiveCount)
                    .description("Active worker threads in the recent-activity bounded executor")
                    .register(meterRegistry);
            Gauge.builder("recent_activity_executor.pool_size", executor, e -> (double) e.getPoolSize())
                    .description("Current pool size (core ≤ poolSize ≤ max)")
                    .register(meterRegistry);
        }

        return executor;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
