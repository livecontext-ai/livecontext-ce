package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.common.web.PlanLimits;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService;
import com.apimarketplace.orchestrator.trigger.queue.PlanPriorityMapper;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-level schedule overview for the Triggers settings page.
 * Lists all schedules across all workflows for the authenticated user.
 * Uses TriggerClient to access schedule data in trigger-service.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleOverviewController {

    private final TriggerClient triggerClient;
    private final WorkflowRepository workflowRepository;
    private final boolean planLimitsEnabled;

    public ScheduleOverviewController(
            TriggerClient triggerClient,
            WorkflowRepository workflowRepository,
            @Value("${plan-limits.enabled:true}") boolean planLimitsEnabled) {
        this.triggerClient = triggerClient;
        this.workflowRepository = workflowRepository;
        this.planLimitsEnabled = planLimitsEnabled;
    }

    /**
     * Optional, so the tests that build this controller by hand keep working: without it a
     * stopped schedule leaves its incident to close by itself after a week, as before.
     */
    private NotificationDeliveryService notificationDelivery;

    @Autowired(required = false)
    public void setNotificationDelivery(NotificationDeliveryService notificationDelivery) {
        this.notificationDelivery = notificationDelivery;
    }

    /**
     * The owner paused or deleted a schedule of this workflow: its failure incident closes and
     * the "still failing" reminders stop. If another trigger keeps it failing, that is a new alert.
     */
    private void workflowStopped(UUID workflowId) {
        if (notificationDelivery != null) notificationDelivery.onWorkflowStopped(workflowId);
    }

    /**
     * List all schedules for the authenticated tenant.
     */
    @GetMapping
    public ResponseEntity<List<ScheduleOverviewResponse>> getAll(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<ScheduledExecutionDto> schedules = hasOrganizationScope(organizationId)
                ? triggerClient.getSchedulesByOrganization(organizationId)
                : triggerClient.getSchedulesByTenant(tenantId);
        List<ScheduledExecutionDto> visibleSchedules = schedules.stream()
                .filter(ScheduledExecutionDto::getIsActive)
                .toList();

        // Build workflowId -> name map for display (filter out null workflowIds for standalone schedules)
        Map<UUID, String> workflowNames = visibleSchedules.stream()
                .map(ScheduledExecutionDto::getWorkflowId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> workflowRepository.findById(id)
                                .map(WorkflowEntity::getName)
                                .orElse("Unknown"),
                        (a, b) -> a
                ));

        List<ScheduleOverviewResponse> result = visibleSchedules.stream()
                .map(s -> ScheduleOverviewResponse.fromDto(s,
                        s.getWorkflowId() != null ? workflowNames.getOrDefault(s.getWorkflowId(), "Unknown") : null))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get schedule count + max limit for the tenant.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {
        int maxPerUser = planLimitsEnabled ? PlanPriorityMapper.getMaxTriggerEndpoints(userPlan) : PlanLimits.UNLIMITED;
        long count = triggerClient.countSchedulesByTenant(tenantId, organizationId);
        return ResponseEntity.ok(Map.of(
                "currentCount", count,
                "maxPerUser", maxPerUser
        ));
    }

    /**
     * Toggle a schedule enabled/disabled.
     */
    @PostMapping("/{scheduleId}/toggle")
    public ResponseEntity<?> toggle(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID scheduleId,
            @RequestBody Map<String, Boolean> body) {
        ResponseEntity<?> denied = refuseViewer(orgRole, organizationId, tenantId, "pause or resume a schedule");
        if (denied != null) return denied;
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        ScheduledExecutionDto result = triggerClient.toggleSchedule(scheduleId, enabled, organizationId, tenantId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        if (!enabled && !result.isEnabled()) workflowStopped(result.getWorkflowId());
        return ResponseEntity.ok(Map.of("success", true, "enabled", enabled));
    }

    /**
     * Create a standalone schedule.
     */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestBody StandaloneScheduleRequest request) {
        ResponseEntity<?> denied = refuseViewer(orgRole, organizationId, tenantId, "create a schedule");
        if (denied != null) return denied;
        // No pre-check on limit here - trigger-service's create path dedups
        // by (tenantId, sourceNodeId) BEFORE checking the limit, so a refresh
        // at-limit returns the existing row instead of 400. Pre-checking here
        // would reintroduce the quota-burn bug the V136 fix targets.
        ScheduledExecutionDto result;
        try {
            result = triggerClient.createStandaloneSchedule(tenantId, userPlan, request, organizationId);
        } catch (Exception e) {
            // Surface the limit-reached 400 shape when trigger-service rejects.
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Resource limit reached") || msg.contains("limit reached")) {
                int maxPerUser = planLimitsEnabled ? PlanPriorityMapper.getMaxTriggerEndpoints(userPlan) : PlanLimits.UNLIMITED;
                long currentCount = triggerClient.countSchedulesByTenant(tenantId, organizationId);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Schedule limit reached",
                        "currentCount", currentCount,
                        "maxPerUser", maxPerUser));
            }
            throw e;
        }
        if (result == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to create schedule"));
        }
        return ResponseEntity.ok(ScheduleOverviewResponse.fromDto(result, null));
    }

    /**
     * Update a standalone schedule.
     */
    @PutMapping("/{scheduleId}")
    public ResponseEntity<?> update(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID scheduleId,
            @RequestBody StandaloneScheduleRequest request) {
        ResponseEntity<?> denied = refuseViewer(orgRole, organizationId, tenantId, "edit a schedule");
        if (denied != null) return denied;
        ScheduledExecutionDto result = triggerClient.updateStandaloneSchedule(tenantId, scheduleId, request, organizationId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ScheduleOverviewResponse.fromDto(result, null));
    }

    /**
     * Validate a cron expression and return its description + next 3 firings.
     *
     * <p>Single source of truth for cron interpretation in the UI. The frontend
     * calls this on every input change in the schedule inspector and renders the
     * response verbatim (no client-side cron parsing). Backed by Spring's
     * {@code CronExpression} via {@code ScheduleCronParser} on trigger-service.
     *
     * <p>Response shape:
     * <pre>{@code
     * { "valid": true,
     *   "description": "Every 2 hours",
     *   "nextExecutions": ["2026-05-15T20:00:00Z", "2026-05-15T22:00:00Z", "2026-05-16T00:00:00Z"] }
     * }</pre>
     * On invalid input: {@code { "valid": false }}.
     */
    @PostMapping("/validate-cron")
    public ResponseEntity<Map<String, Object>> validateCron(@RequestBody Map<String, String> body) {
        String cron = body.get("cron");
        String timezone = body.getOrDefault("timezone", "UTC");
        return ResponseEntity.ok(triggerClient.validateCron(cron, timezone));
    }

    /**
     * Delete a schedule (v5: archive - not hard-delete).
     *
     * <p>Routes through {@code triggerClient.archiveScheduleById} with reason
     * USER_DELETED so the trigger_state_audit_log captures the deletion event
     * and execution_count is preserved indefinitely. Archived rows are not
     * currently auto-reaped - they persist until manual cleanup or a future
     * ARCHIVED-row reaper.
     */
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<?> delete(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID scheduleId) {
        ResponseEntity<?> denied = refuseViewer(orgRole, organizationId, tenantId, "delete a schedule");
        if (denied != null) return denied;
        // Read BEFORE the archive, which answers a boolean only. Used for nothing but the id of
        // the workflow whose incident to close, and only once the scoped archive has succeeded.
        ScheduledExecutionDto schedule = notificationDelivery != null ? triggerClient.getSchedule(scheduleId) : null;
        boolean archived = triggerClient.archiveScheduleById(scheduleId, "USER_DELETED", organizationId, tenantId);
        if (!archived) {
            return ResponseEntity.notFound().build();
        }
        if (schedule != null) workflowStopped(schedule.getWorkflowId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Refuse an org VIEWER, returning the 403 body when they must be stopped and null when
     * they may proceed.
     *
     * <p>Applied to EVERY write on this controller, which is the whole point. It was first
     * added to {@code toggle} alone, on the reasoning that pausing a schedule stops a
     * workspace's automation running - and that argument covers {@code create},
     * {@code update} and especially {@code delete}, which archives the row permanently. A
     * gate on one of four writes in the same file is not a gate. The frontend's
     * {@code canMutate} hides the buttons, which is not a boundary either.
     *
     * <p>Only applies inside an organization workspace; a personal workspace has no roles.
     */
    private static ResponseEntity<?> refuseViewer(String orgRole, String organizationId,
                                                  String tenantId, String action) {
        if (organizationId != null && orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "reason", "VIEWER_ROLE",
                            "error", "VIEWER role cannot modify schedules"));
        }
        return null;
    }

    public record ScheduleOverviewResponse(
            String id,
            String name,
            String workflowId,
            String workflowName,
            String triggerId,
            String cronExpression,
            String timezone,
            boolean enabled,
            Integer maxExecutions,
            int executionCount,
            String nextExecutionAt,
            String lastExecutionAt,
            String createdAt,
            String description,
            boolean isActive,
            String sourceNodeId
    ) {
        public static ScheduleOverviewResponse fromDto(ScheduledExecutionDto dto, String resolvedWorkflowName) {
            // Prefer name from entity, fallback to triggerId without prefix
            String displayName = dto.getName() != null ? dto.getName()
                    : (dto.getTriggerId() != null ? dto.getTriggerId().replace("trigger:", "") : "Schedule");
            // Prefer workflowName from entity, fallback to resolved name from DB
            String wfName = dto.getWorkflowName() != null ? dto.getWorkflowName() : resolvedWorkflowName;
            return new ScheduleOverviewResponse(
                    dto.getId().toString(),
                    displayName,
                    dto.getWorkflowId() != null ? dto.getWorkflowId().toString() : null,
                    wfName,
                    dto.getTriggerId(),
                    dto.getCronExpression(),
                    dto.getTimezone(),
                    dto.isEnabled(),
                    dto.getMaxExecutions(),
                    dto.getExecutionCount(),
                    dto.getNextExecutionAt() != null ? dto.getNextExecutionAt().toString() : null,
                    dto.getLastExecutionAt() != null ? dto.getLastExecutionAt().toString() : null,
                    dto.getCreatedAt() != null ? dto.getCreatedAt().toString() : null,
                    dto.getDescription(),
                    dto.getIsActive(),
                    dto.getSourceNodeId()
            );
        }
    }

    private static boolean hasOrganizationScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank();
    }
}
