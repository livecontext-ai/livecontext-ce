package com.apimarketplace.agent.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweeper for in_review tasks whose retry schedule was lost across
 * service restarts (the retry was an in-memory CompletableFuture). Fires once
 * per minute at second 15 (cron {@code "15 * * * * *"}); distributed-safe via
 * ShedLock so only one agent-service instance sweeps at a time.
 *
 * See bug #7 in TASK_TEST_ERRORS.md.
 */
@Component
public class TaskReviewSweeper {

    private static final Logger logger = LoggerFactory.getLogger(TaskReviewSweeper.class);

    private final AgentTaskService taskService;

    @Value("${agent.task-review.sweep.stale-seconds:120}")
    private long staleThresholdSeconds;

    @Value("${agent.task-review.sweep.batch-size:50}")
    private int batchSize;

    public TaskReviewSweeper(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(cron = "15 * * * * *")
    @SchedulerLock(name = "taskReviewSweeper_tick",
                   lockAtMostFor = "PT55S",
                   lockAtLeastFor = "PT10S")
    public void tick() {
        try {
            int triggered = taskService.sweepStuckReviewTasks(staleThresholdSeconds, batchSize);
            if (triggered > 0) {
                logger.info("[TaskReview][Sweeper] Tick: re-triggered {} stuck review task(s)", triggered);
            }
        } catch (Exception e) {
            logger.error("[TaskReview][Sweeper] Tick failed", e);
        }
    }
}
