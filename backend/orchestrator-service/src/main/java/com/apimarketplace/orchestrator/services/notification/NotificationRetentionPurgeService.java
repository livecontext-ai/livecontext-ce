package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.repository.NotificationRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Daily retention purge - removes notifications older than 30 days.
 *
 * <p><b>Multi-replica safety</b>: orchestrator runs on multiple replicas in
 * production (per scaling roadmap). {@link SchedulerLock} ensures only the
 * leader executes the DELETE. Lock-table is {@code orchestrator.shedlock}
 * (configured in {@link com.apimarketplace.orchestrator.config.ShedLockConfig}).
 *
 * <p><b>Chunked DELETE</b>: bounded {@code LIMIT} per statement avoids
 * long-held row locks on hot tenants. The loop runs until a chunk returns
 * fewer than {@link #BATCH_SIZE} rows.
 *
 * <p><b>Cron timezone</b>: pinned to {@code UTC} explicitly; do not rely on
 * the JVM default timezone.
 */
@Service
public class NotificationRetentionPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationRetentionPurgeService.class);

    static final Duration RETENTION = Duration.ofDays(30);
    static final int BATCH_SIZE = 10_000;
    private static final int MAX_LOOP_ITERATIONS = 100; // safety bound: 1M rows max per run

    private final NotificationRepository notificationRepository;
    /**
     * Self-reference via {@link Lazy @Lazy} so the {@code @Transactional}
     * boundary on {@link #deleteChunk(Instant)} actually fires - Spring's AOP
     * proxy is bypassed by direct {@code this.method()} calls. Without this
     * indirection the per-chunk-tx promise (lock release between iterations)
     * is silently a no-op (caught by audit, all 5 reviewers convergent).
     */
    private final NotificationRetentionPurgeService self;

    public NotificationRetentionPurgeService(NotificationRepository notificationRepository,
                                              @Lazy NotificationRetentionPurgeService self) {
        this.notificationRepository = notificationRepository;
        this.self = self;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "purge-notifications",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void purgeExpiredNotifications() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int totalDeleted = 0;
        int iterations = 0;

        while (iterations++ < MAX_LOOP_ITERATIONS) {
            int deleted = self.deleteChunk(cutoff);
            totalDeleted += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }

        if (iterations >= MAX_LOOP_ITERATIONS) {
            logger.warn("[notification-purge] hit MAX_LOOP_ITERATIONS={} (deleted={}) - " +
                    "tail rows older than {} remain for the next run",
                    MAX_LOOP_ITERATIONS, totalDeleted, cutoff);
        } else if (totalDeleted > 0) {
            logger.info("[notification-purge] removed {} rows older than {} (iterations={})",
                    totalDeleted, cutoff, iterations);
        }
    }

    /**
     * Each chunk in its own transaction so locks are released between
     * iterations under heavy contention. Called via {@link #self} to engage
     * Spring's transactional proxy.
     */
    @Transactional
    public int deleteChunk(Instant cutoff) {
        return notificationRepository.deleteOlderThanChunked(cutoff, BATCH_SIZE);
    }
}
