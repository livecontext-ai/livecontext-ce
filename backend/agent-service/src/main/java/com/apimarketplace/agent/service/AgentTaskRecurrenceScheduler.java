package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.common.web.TenantResolver;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Ticks once per minute, fires any due task recurrences. Distributed-safe
 * via ShedLock so that multiple agent-service instances don't double-fire.
 *
 * @see AgentTaskRecurrenceService#fireOnce(AgentTaskRecurrenceEntity)
 */
@Component
public class AgentTaskRecurrenceScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskRecurrenceScheduler.class);

    private final AgentTaskRecurrenceService recurrenceService;

    public AgentTaskRecurrenceScheduler(AgentTaskRecurrenceService recurrenceService) {
        this.recurrenceService = recurrenceService;
    }

    @Scheduled(cron = "5 * * * * *")
    @SchedulerLock(name = "agentTaskRecurrence_tick",
                   lockAtMostFor = "PT55S",
                   lockAtLeastFor = "PT10S")
    public void tick() {
        Instant now = Instant.now();
        List<AgentTaskRecurrenceEntity> due;
        try {
            due = recurrenceService.findDue(now);
        } catch (Exception e) {
            logger.error("Recurrence tick: failed to fetch due recurrences", e);
            return;
        }
        if (due.isEmpty()) return;

        logger.info("Recurrence tick: {} due", due.size());
        for (AgentTaskRecurrenceEntity r : due) {
            // Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-5/H-2, 2026-05-19): the
            // @Scheduled thread has no RequestContextHolder. Bind the
            // recurrence's organization_id so the OrgScopedEntityListener
            // filet stamps any new row (spawned task, audit event) with a
            // concrete UUID for the V261 NOT NULL columns. fireOnce already
            // sets task.setOrganizationId explicitly; this is defense-in-depth
            // for any downstream persist that doesn't stamp.
            TenantResolver.runWithOrgScope(r.getOrganizationId(), () -> {
                try {
                    recurrenceService.fireOnce(r);
                } catch (Exception e) {
                    logger.error("Recurrence {} tick failed: {}", r.getId(), e.getMessage(), e);
                }
            });
        }
    }
}
