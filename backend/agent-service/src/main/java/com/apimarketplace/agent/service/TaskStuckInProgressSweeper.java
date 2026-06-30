package com.apimarketplace.agent.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backstop reaper for in_progress tasks whose assignee execution was orphaned (worker or pod death,
 * or a lost synchronous downstream call), so neither the in-loop inactivity watchdog nor the normal
 * completion path ever returned a result and the task row is stuck in_progress.
 *
 * <p>This is a SAFETY NET, not the primary timeout. A hung agent loop is already stopped within the
 * per-agent inactivity window by the in-process watchdog, which returns INACTIVITY_TIMEOUT and fails
 * the task through the normal path. The stale threshold is therefore deliberately GENEROUS (default
 * 2.5h, well above the maximum agent executionTimeout of 2h) so a legitimately long run is never
 * reaped - only a genuinely orphaned task is.</p>
 *
 * <p>Fires once per minute at second 45 (offset from {@link TaskReviewSweeper}'s second 15 so the two
 * sweeps never contend); distributed-safe via ShedLock so only one agent-service instance reaps.</p>
 */
@Component
public class TaskStuckInProgressSweeper {

    private static final Logger logger = LoggerFactory.getLogger(TaskStuckInProgressSweeper.class);

    private final AgentTaskService taskService;

    @Value("${agent.task-progress.sweep.stale-seconds:9000}")
    private long staleThresholdSeconds;

    @Value("${agent.task-progress.sweep.batch-size:50}")
    private int batchSize;

    @Value("${agent.task-progress.sweep.enabled:true}")
    private boolean enabled;

    public TaskStuckInProgressSweeper(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(cron = "45 * * * * *")
    @SchedulerLock(name = "taskStuckInProgressSweeper_tick",
                   lockAtMostFor = "PT55S",
                   lockAtLeastFor = "PT10S")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int failed = taskService.sweepStuckInProgressTasks(staleThresholdSeconds, batchSize);
            if (failed > 0) {
                logger.info("[TaskProgress][Sweeper] Tick: auto-failed {} orphaned in_progress task(s)", failed);
            }
        } catch (Exception e) {
            logger.error("[TaskProgress][Sweeper] Tick failed", e);
        }
    }
}
