package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.client.dto.ScheduleStatusResponse;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * REST controller for schedule CRUD operations.
 * Execute-now stays in orchestrator (needs ReusableTriggerService).
 * Disabled in monolith mode (orchestrator ScheduleController handles everything).
 */
@RestController
@RequestMapping("/api/v2/workflows/{workflowId}/schedule")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class ScheduleController {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleController.class);

    private final ScheduledExecutionRepository scheduleRepository;
    private final ScheduleCronParser cronParser;
    private final TenantResolver tenantResolver;
    private final PlanLimitHelper planLimitHelper;
    private final TriggerLifecycleManager triggerLifecycleManager;

    public ScheduleController(ScheduledExecutionRepository scheduleRepository,
                              ScheduleCronParser cronParser,
                              TenantResolver tenantResolver,
                              PlanLimitHelper planLimitHelper,
                              TriggerLifecycleManager triggerLifecycleManager) {
        this.scheduleRepository = scheduleRepository;
        this.cronParser = cronParser;
        this.tenantResolver = tenantResolver;
        this.planLimitHelper = planLimitHelper;
        this.triggerLifecycleManager = triggerLifecycleManager;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getScheduleStatus(
            @PathVariable("workflowId") UUID workflowId,
            HttpServletRequest request) {
        List<ScheduledExecutionEntity> schedules = scheduleRepository.findByWorkflowId(workflowId);
        if (schedules.isEmpty()) {
            return ResponseEntity.ok(Map.of("hasSchedule", false));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasSchedule", true);

        Map<String, Object> schedulesMap = new LinkedHashMap<>();
        for (ScheduledExecutionEntity schedule : schedules) {
            schedulesMap.put(schedule.getTriggerId(), toStatusResponse(schedule));
        }
        result.put("schedules", schedulesMap);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/status/{triggerId}")
    public ResponseEntity<ScheduleStatusResponse> getScheduleStatusForTrigger(
            @PathVariable("workflowId") UUID workflowId,
            @PathVariable("triggerId") String triggerId) {
        List<ScheduledExecutionEntity> results = scheduleRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toStatusResponse(results.get(0)));
    }

    @PostMapping("/{triggerId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> createOrUpdateSchedule(
            @PathVariable("workflowId") UUID workflowId,
            @PathVariable("triggerId") String triggerId,
            @RequestBody ScheduleCreateRequest body,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = tenantResolver.resolveOrgId(request);
        if (organizationId == null || organizationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "X-Organization-ID header is required (post-V261)"));
        }

        if (!cronParser.isValid(body.cron())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid cron expression"));
        }

        Instant nextExecution = cronParser.getNextExecution(body.cron(), body.timezone());
        if (nextExecution == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not calculate next execution time"));
        }

        // Plan-based limit check (only for new schedules).
        // Quota excludes orphans (workflow_id IS NULL AND agent_entity_id IS NULL).
        // Abandoned builder drafts are reaped after 24h by StandaloneTriggerReaperService.
        // Strict-org count post-V261 (every row carries a non-null org).
        boolean isNew = !scheduleRepository.existsByWorkflowIdAndTriggerId(workflowId, triggerId);
        if (isNew) {
            String userPlan = request.getHeader("X-User-Plan");
            int maxSchedules = planLimitHelper.getMaxEndpoints(userPlan);
            long currentCount = scheduleRepository.countActiveByOrganizationIdStrict(organizationId);
            if (currentCount >= maxSchedules) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Schedule limit reached",
                        "currentCount", currentCount,
                        "maxPerUser", maxSchedules
                ));
            }
        }

        // Find existing - use list query to gracefully handle any pre-migration duplicates
        List<ScheduledExecutionEntity> existing = scheduleRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        ScheduledExecutionEntity schedule;
        if (existing.isEmpty()) {
            schedule = new ScheduledExecutionEntity(workflowId, triggerId, tenantId,
                    body.cron(), body.timezone(), nextExecution);
        } else {
            existing.sort(Comparator.comparingInt(ScheduledExecutionEntity::getExecutionCount).reversed());
            schedule = existing.get(0);
            if (existing.size() > 1) {
                logger.warn("[Schedule] Cleaning up {} duplicate(s) for workflow={} trigger={}",
                        existing.size() - 1, workflowId, triggerId);
                scheduleRepository.deleteAll(existing.subList(1, existing.size()));
            }
        }

        // Stamp organization_id on every save (new + update). The entity constructor
        // does not take orgId, so without this line the row lands NULL-org and is
        // invisible to org-scoped list endpoints (bell Triggers tab via
        // ActiveAutomationsService.getSchedulesByOrganization). On update, also
        // backfill NULL-org legacy rows when the caller is now in an org workspace -
        // a defensive cleanup mirroring V218's intent for rows created post-V218.
        if (schedule.getOrganizationId() == null) {
            schedule.setOrganizationId(organizationId);
        }
        schedule.setCronExpression(body.cron());
        schedule.setTimezone(body.timezone() != null ? body.timezone() : "UTC");
        schedule.setMaxExecutions(body.maxExecutions());
        schedule.setEnabled(body.enabled());
        schedule.setNextExecutionAt(nextExecution);
        schedule.setUpdatedAt(Instant.now());

        if (body.expiresInDays() != null && body.expiresInDays() > 0) {
            schedule.setExpiresAt(Instant.now().plus(body.expiresInDays(), ChronoUnit.DAYS));
        }

        scheduleRepository.save(schedule);
        logger.info("Created/updated schedule for workflow {} trigger {}: cron={}",
                workflowId, triggerId, body.cron());

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "schedule", toStatusResponse(schedule),
                "description", cronParser.getDescription(body.cron())));
    }

    @PostMapping("/toggle/{triggerId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggleSchedule(
            @PathVariable("workflowId") UUID workflowId,
            @PathVariable("triggerId") String triggerId,
            @RequestBody Map<String, Boolean> body) {
        List<ScheduledExecutionEntity> results = scheduleRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ScheduledExecutionEntity schedule = results.get(0);

        // getOrDefault returns the default only for an ABSENT key; a body of {"enabled": null}
        // returns null and NPEs on unboxing (500). Treat a present-but-null (or absent) value as
        // a toggle of the current state, mirroring InternalTriggerController's null-safe pattern.
        Boolean requested = body.get("enabled");
        boolean enabled = requested != null ? requested : !schedule.isEnabled();
        schedule.setEnabled(enabled);

        if (enabled) {
            Instant nextExecution = cronParser.getNextExecution(schedule.getCronExpression(), schedule.getTimezone());
            if (nextExecution != null) {
                schedule.setNextExecutionAt(nextExecution);
            }
        }

        schedule.setUpdatedAt(Instant.now());
        scheduleRepository.save(schedule);

        return ResponseEntity.ok(Map.of("enabled", enabled, "schedule", toStatusResponse(schedule)));
    }

    /**
     * Delete all schedules for a workflow (v5: archive - not hard-delete).
     * Routes each row through {@code TriggerLifecycleManager.archiveSchedule}
     * with reason {@code USER_DELETED} so the audit log + execution_count survive.
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> deleteAllSchedules(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        // Scope check - audit 2026-05-16: prior implementation archived
        // schedules by workflowId alone with NO tenant/org filter, allowing
        // cross-tenant epoch-state corruption.
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        List<ScheduledExecutionEntity> rows = scheduleRepository.findByWorkflowId(workflowId);
        if (!rows.isEmpty() && !isAnyRowInScope(rows, tenantId, orgId)) {
            // None of the schedules are visible to the caller - return 404 to
            // avoid leaking existence of cross-scope rows.
            return ResponseEntity.notFound().build();
        }
        for (ScheduledExecutionEntity s : rows) {
            if (s.getState() != TriggerState.ARCHIVED && isRowInScope(s, tenantId, orgId)) {
                triggerLifecycleManager.archiveSchedule(s.getId(),
                        TriggerLifecycleManager.Reason.USER_DELETED,
                        TriggerLifecycleManager.Source.ADMIN);
            }
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Delete a single schedule (v5: archive - not hard-delete).
     */
    @DeleteMapping("/{triggerId}")
    @Transactional
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable("workflowId") UUID workflowId,
            @PathVariable("triggerId") String triggerId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        List<ScheduledExecutionEntity> results = scheduleRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        if (!results.isEmpty() && !isAnyRowInScope(results, tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }
        for (ScheduledExecutionEntity s : results) {
            if (s.getState() != TriggerState.ARCHIVED && isRowInScope(s, tenantId, orgId)) {
                triggerLifecycleManager.archiveSchedule(s.getId(),
                        TriggerLifecycleManager.Reason.USER_DELETED,
                        TriggerLifecycleManager.Source.ADMIN);
            }
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Strict-isolation scope predicate for a schedule row, aligned 2026-05-18
     * with {@link ScopeGuard#isInStrictScope}. Used by the DELETE endpoints to
     * refuse cross-workspace archives - caller in OrgA cannot delete their
     * personal schedule (and vice versa) via this path.
     */
    private static boolean isRowInScope(ScheduledExecutionEntity s, String tenantId, String orgId) {
        if (s == null) return false;
        return ScopeGuard.isInStrictScope(
                tenantId, orgId, s.getTenantId(), s.getOrganizationId());
    }

    private static boolean isAnyRowInScope(List<ScheduledExecutionEntity> rows, String tenantId, String orgId) {
        for (ScheduledExecutionEntity s : rows) {
            if (isRowInScope(s, tenantId, orgId)) return true;
        }
        return false;
    }

    private ScheduleStatusResponse toStatusResponse(ScheduledExecutionEntity schedule) {
        String status = schedule.hasReachedMaxExecutions() ? "completed"
                : (schedule.isEnabled() ? "active" : "paused");
        return new ScheduleStatusResponse(
                schedule.getCronExpression(),
                schedule.getTimezone(),
                schedule.isEnabled(),
                schedule.getLastExecutionAt(),
                schedule.getNextExecutionAt(),
                schedule.getExecutionCount(),
                status,
                schedule.getMaxExecutions()
        );
    }
}
