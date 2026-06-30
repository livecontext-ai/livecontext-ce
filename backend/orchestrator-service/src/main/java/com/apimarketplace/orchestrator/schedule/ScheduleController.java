package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.PlanLimits;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.queue.PlanPriorityMapper;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for schedule execution operations (execute-now, toggle).
 * Schedule CRUD is handled by trigger-service.
 * This controller stays in orchestrator because execute-now requires ReusableTriggerService.
 */
@RestController
@RequestMapping("/api/v2/workflows/{workflowId}/schedule")
public class ScheduleController {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleController.class);

    private final TriggerClient triggerClient;
    private final ScheduleExecutorService scheduleExecutorService;
    private final WorkflowRepository workflowRepository;
    private final boolean planLimitsEnabled;

    public ScheduleController(
            TriggerClient triggerClient,
            ScheduleExecutorService scheduleExecutorService,
            WorkflowRepository workflowRepository,
            @Value("${plan-limits.enabled:true}") boolean planLimitsEnabled) {
        this.triggerClient = triggerClient;
        this.scheduleExecutorService = scheduleExecutorService;
        this.workflowRepository = workflowRepository;
        this.planLimitsEnabled = planLimitsEnabled;
    }

    /**
     * Strict-isolation scope predicate for schedules' parent workflow,
     * aligned 2026-05-18 with {@link ScopeGuard#isInStrictScope}. Every
     * schedule endpoint funnels through {@link #guardWorkflowScope} which
     * calls this predicate before any read/write.
     */
    private boolean isWorkflowInScope(WorkflowEntity workflow, String tenantId, String orgId) {
        if (workflow == null) return false;
        return ScopeGuard.isInStrictScope(tenantId, orgId,
                workflow.getTenantId(), workflow.getOrganizationId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String scheduleOrganizationId(WorkflowEntity workflow, String headerOrgId) {
        if (workflow != null && hasText(workflow.getOrganizationId())) {
            return workflow.getOrganizationId();
        }
        return headerOrgId;
    }

    private ResponseEntity<?> guardWorkflowScope(UUID workflowId, String tenantId, String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-User-ID"));
        }
        WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null) return ResponseEntity.notFound().build();
        if (!isWorkflowInScope(workflow, tenantId, orgId)) {
            logger.warn("[SCOPE] ScheduleController cross-tenant blocked: workflowId={} caller={} orgId={}",
                    workflowId, tenantId, orgId);
            return ResponseEntity.notFound().build();
        }
        return null;
    }

    /**
     * Get all schedules for a workflow (multi-DAG).
     */
    @GetMapping("/status")
    public ResponseEntity<?> getScheduleStatus(
            @PathVariable String workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        logger.info("[ScheduleController] Getting status for workflowId: {}", workflowId);
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            List<ScheduledExecutionDto> schedules = triggerClient.getSchedulesByWorkflow(
                    id, scheduleOrganizationId(workflow, orgId));

            if (schedules.isEmpty()) {
                logger.warn("[ScheduleController] No schedules found for workflow {}", workflowId);
                return ResponseEntity.ok(Map.of(
                        "exists", false,
                        "message", "No schedules configured for this workflow"
                ));
            }

            List<Map<String, Object>> statuses = schedules.stream()
                .map(schedule -> Map.<String, Object>of(
                    "triggerId", schedule.getTriggerId(),
                    "status", scheduleExecutorService.getScheduleStatus(schedule)
                ))
                .collect(Collectors.toList());

            logger.info("[ScheduleController] Found {} schedule(s) for workflow {}", schedules.size(), workflowId);
            return ResponseEntity.ok(Map.of(
                "exists", true,
                "schedules", statuses
            ));
        } catch (IllegalArgumentException e) {
            logger.error("[ScheduleController] Invalid workflow ID: {}", workflowId);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID"));
        }
    }

    /**
     * Get status for a specific schedule trigger.
     */
    @GetMapping("/status/{triggerId}")
    public ResponseEntity<?> getScheduleStatusForTrigger(
            @PathVariable String workflowId,
            @PathVariable String triggerId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            ScheduledExecutionDto schedule = triggerClient.getScheduleByWorkflowAndTrigger(
                    id, triggerId, scheduleOrganizationId(workflow, orgId));
            if (schedule != null) {
                return ResponseEntity.ok(scheduleExecutorService.getScheduleStatus(schedule));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID"));
        }
    }

    /**
     * Toggle schedule enabled/disabled for a specific trigger.
     */
    @PostMapping("/toggle/{triggerId}")
    public ResponseEntity<?> toggleSchedule(
            @PathVariable String workflowId,
            @PathVariable String triggerId,
            @RequestBody ToggleRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            ScheduledExecutionDto schedule = triggerClient.getScheduleByWorkflowAndTrigger(
                    id, triggerId, scheduleOrganizationId(workflow, orgId));
            if (schedule == null) {
                return ResponseEntity.notFound().build();
            }

            ScheduledExecutionDto updated = triggerClient.toggleSchedule(schedule.getId(), request.enabled(), orgId, tenantId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "triggerId", triggerId,
                    "enabled", updated != null ? updated.isEnabled() : request.enabled()
            ));
        } catch (Exception e) {
            logger.error("Error toggling schedule: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Execute a specific schedule immediately (bypass cron timing).
     * This endpoint stays in orchestrator because it calls ReusableTriggerService.
     */
    @PostMapping("/execute-now/{triggerId}")
    public ResponseEntity<?> executeNow(
            @PathVariable String workflowId,
            @PathVariable String triggerId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            ScheduledExecutionDto schedule = triggerClient.getScheduleByWorkflowAndTrigger(
                    id, triggerId, scheduleOrganizationId(workflow, orgId));
            if (schedule == null) {
                return ResponseEntity.notFound().build();
            }

            TriggerExecutionResult result = scheduleExecutorService.executeNow(schedule);
            if (result.success()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "triggerId", triggerId,
                        "runId", result.runId() != null ? result.runId() : "",
                        "message", "Schedule executed successfully"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", result.message()
                ));
            }
        } catch (Exception e) {
            logger.error("Error executing schedule now: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create or update a schedule for a trigger.
     * Proxies to trigger-service with plan-based limit enforcement.
     *
     * <p>Pin gate: rejects (400) when the workflow has no pinned production version.
     * Contract: a schedule row is ACTIVE iff {@code workflow.pinned_version IS NOT NULL}.
     * Without this gate, any caller could create an auto-firing row on a workflow
     * the user never made "live" via the {@code ApplicationActivationButton} toggle.
     */
    @PostMapping("/{triggerId}")
    public ResponseEntity<?> createOrUpdateSchedule(
            @PathVariable String workflowId,
            @PathVariable String triggerId,
            @RequestBody ScheduleCreateRequest body,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {
        try {
            UUID id = UUID.fromString(workflowId);

            // Pin gate - schedules follow the live toggle. See class-level contract.
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            if (workflow == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Workflow not found"));
            }
            // Audit 2026-05-17 round-3: positive owner-or-org scope predicate.
            if (!isWorkflowInScope(workflow, tenantId, orgId)) {
                logger.warn("[SCOPE] createOrUpdateSchedule cross-tenant blocked: workflowId={} caller={} orgId={}",
                        workflowId, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            if (workflow.getPinnedVersion() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Workflow is not pinned - toggle live on the application first",
                        "kind", "WORKFLOW_NOT_PINNED"));
            }

            // Plan-based limit check for new schedules
            ScheduledExecutionDto existing = triggerClient.getScheduleByWorkflowAndTrigger(
                    id, triggerId, scheduleOrganizationId(workflow, orgId));
            if (existing == null) {
                int maxSchedules = planLimitsEnabled ? PlanPriorityMapper.getMaxTriggerEndpoints(userPlan) : PlanLimits.UNLIMITED;
                long currentCount = triggerClient.countSchedulesByTenant(tenantId, orgId);
                if (currentCount >= maxSchedules) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Schedule limit reached",
                            "currentCount", currentCount,
                            "maxPerUser", maxSchedules
                    ));
                }
            }

            // Proxy to trigger-service
            ScheduledExecutionDto result = triggerClient.createOrUpdateSchedule(
                    id, triggerId, tenantId, scheduleOrganizationId(workflow, orgId), body, null, null);
            if (result == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Failed to create schedule"));
            }

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "schedule", result
            ));
        } catch (Exception e) {
            logger.error("Error creating schedule: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete all schedules for a workflow (v5: archive - not hard-delete).
     * The trigger rows are transitioned to ARCHIVED with reason USER_DELETED so
     * the audit log + execution_count survive for forensic / replay purposes.
     */
    @DeleteMapping
    public ResponseEntity<?> deleteAllSchedules(
            @PathVariable String workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            int archived = triggerClient.archiveSchedulesByWorkflow(id, "USER_DELETED");
            return ResponseEntity.ok(Map.of("success", true, "archived", archived));
        } catch (Exception e) {
            logger.error("Error archiving schedules: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a specific schedule for a trigger (v5: archive - not hard-delete).
     */
    @DeleteMapping("/{triggerId}")
    public ResponseEntity<?> deleteSchedule(
            @PathVariable String workflowId,
            @PathVariable String triggerId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            ResponseEntity<?> scopeBlock = guardWorkflowScope(id, tenantId, orgId);
            if (scopeBlock != null) return scopeBlock;
            WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
            ScheduledExecutionDto schedule = triggerClient.getScheduleByWorkflowAndTrigger(
                    id, triggerId, scheduleOrganizationId(workflow, orgId));
            if (schedule != null) {
                triggerClient.archiveScheduleById(schedule.getId(), "USER_DELETED", orgId, tenantId);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            logger.error("Error archiving schedule: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Request DTOs
    public record ToggleRequest(boolean enabled) {}
}
