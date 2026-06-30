package com.apimarketplace.agent.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention sweep for the task board's Deleted/trash column: hard-purges tasks that
 * were soft-deleted more than {@code agent.task.deleted-retention.days} (default 30)
 * ago. Fires once a day at 03:30; distributed-safe via ShedLock so only one
 * agent-service instance purges at a time.
 *
 * <p>Mirrors {@link TaskReviewSweeper}'s scheduled-sweep shape. The heavy lifting
 * (batched, per-row isolated deletes) lives in
 * {@link AgentTaskService#purgeExpiredDeletedTasks(long, int)}.
 */
@Component
public class TaskRetentionPurger {

    private static final Logger logger = LoggerFactory.getLogger(TaskRetentionPurger.class);

    private final AgentTaskService taskService;

    @Value("${agent.task.deleted-retention.days:30}")
    private long retentionDays;

    @Value("${agent.task.deleted-retention.batch-size:200}")
    private int batchSize;

    public TaskRetentionPurger(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "taskRetentionPurger_tick",
                   lockAtMostFor = "PT1H",
                   lockAtLeastFor = "PT10S")
    public void tick() {
        try {
            int purged = taskService.purgeExpiredDeletedTasks(retentionDays, batchSize);
            if (purged > 0) {
                logger.info("[TaskRetention] Tick: purged {} trashed task(s) older than {} day(s)",
                        purged, retentionDays);
            }
        } catch (Exception e) {
            logger.error("[TaskRetention] Tick failed", e);
        }
    }
}
