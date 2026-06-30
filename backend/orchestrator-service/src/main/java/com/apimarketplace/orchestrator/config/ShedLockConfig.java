package com.apimarketplace.orchestrator.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;

/**
 * Configuration for ShedLock (distributed scheduler lock) and async signal processing.
 *
 * ShedLock ensures that @Scheduled signal polling methods (timer poll, timeout check,
 * stale claim recovery) run on only one instance at a time in a multi-instance deployment.
 *
 * The signalResumeExecutor provides a dedicated thread pool for async signal resume
 * operations, preventing them from blocking the scheduler thread.
 *
 * @see com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService
 * @see com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
@EnableAsync
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("orchestrator.shedlock")
                .usingDbTime()
                .build()
        );
    }

    /**
     * Dedicated thread pool for signal resume operations.
     *
     * Signal resume (executing successor nodes after a signal resolves) can be
     * long-running. This executor ensures these operations don't block the
     * @Scheduled poller threads.
     */
    @Bean("signalResumeExecutor")
    public TaskExecutor signalResumeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("signal-resume-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated thread pool for step-by-step node execution via WebSocket.
     *
     * SBS execution (triggered by Gateway WS action) runs asynchronously so the
     * WS ack can return immediately. Results are delivered via Redis pub/sub events.
     */
    @Bean("sbsExecutor")
    public TaskExecutor sbsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sbs-exec-");
        executor.initialize();
        return executor;
    }

    /**
     * Plan v4 §8 - bounded thread pool for {@code AsyncSnapshotPublisher}.
     *
     * <p>Spec: 5 threads, queue 100, REJECTED → log + drop + metric
     * {@code async_publish_drop_count}. Decouples snapshot publishing from the
     * writer thread (which today blocks on {@code sendDirectSnapshot} +
     * {@code redisPublisher.publishSnapshot} inside the {@code runAfterCommit}
     * hook) so a slow downstream (SSE consumer, Redis pub/sub) cannot
     * back-pressure the orchestrator hot path.
     *
     * <p>{@link java.util.concurrent.RejectedExecutionHandler} is the
     * {@code AbortPolicy} default - Spring's {@code ThreadPoolTaskExecutor}
     * rethrows {@link java.util.concurrent.RejectedExecutionException} which
     * {@code AsyncSnapshotPublisher} catches and converts to a drop + metric.
     */
    @Bean("snapshotPublishExecutor")
    public TaskExecutor snapshotPublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("snapshot-publish-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
