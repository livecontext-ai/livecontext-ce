package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.TriggerClientException;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for synchronizing scheduled workflow executions.
 *
 * <p><b>Pin-aware design:</b> Only the pinned (production) version's schedule triggers
 * are active. Draft/unpinned version saves NEVER create or modify production schedules.
 * The schedule in DB always reflects the pinned version's cron/timezone/config.
 *
 * <p>Sync is triggered by:
 * <ul>
 *   <li>Pin/unpin a version → re-sync from newly pinned plan (or disable all if unpinned)</li>
 *   <li>Workflow save → re-sync only if the saved version IS the pinned version</li>
 * </ul>
 */
@Service
public class ScheduleSyncService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleSyncService.class);

    private static final String TRIGGER_TYPE_SCHEDULE = "schedule";
    private static final String DEFAULT_CRON = "0 * * * *";
    private static final String DEFAULT_TIMEZONE = "UTC";

    private final TriggerClient triggerClient;
    private final WorkflowPlanVersionService versionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public ScheduleSyncService(
            @Autowired(required = false) TriggerClient triggerClient,
            @Autowired(required = false) WorkflowPlanVersionService versionService) {
        this.triggerClient = triggerClient;
        this.versionService = versionService;
    }

    /**
     * Checks if the workflow plan has at least one schedule trigger.
     */
    public boolean hasScheduleTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) {
            return false;
        }
        return plan.getTriggers().stream()
            .anyMatch(trigger -> TRIGGER_TYPE_SCHEDULE.equalsIgnoreCase(trigger.type()));
    }

    /**
     * Syncs schedules based on the pinned production version.
     *
     * <p>This is the primary entry point. It loads the pinned version's plan
     * and syncs schedules from it. If no version is pinned, all schedules are disabled.
     *
     * <p><b>Concurrency:</b> {@code @Transactional} + per-workflow Postgres
     * advisory lock serialize concurrent sync attempts on the same workflow
     * (browser double-submit, agent + UI hitting saveWorkflow within ms,
     * pin-event + workflow-save race). The lock + tx live on this PUBLIC
     * entry point (proxy-reachable) - placing them on the package-private
     * {@code syncFromPlan} would be a no-op because Spring's default
     * {@code publicMethodsOnly=true} ignores @Transactional on non-public
     * methods AND self-invocations bypass the proxy. v5 audit caught this.
     *
     * @param workflow the workflow entity (must have current pinnedVersion)
     */
    @Transactional
    public void syncFromPinnedVersion(WorkflowEntity workflow) {
        if (triggerClient == null) {
            logger.warn("[ScheduleSync] TriggerClient not available, skipping schedule sync");
            return;
        }

        acquireWorkflowAdvisoryLock(workflow.getId());

        Integer pinnedVersion = workflow.getPinnedVersion();
        String organizationId = workflowOrganizationId(workflow);

        if (pinnedVersion == null) {
            // No pinned version → disable all schedules (don't delete - user may re-pin)
            disableAllSchedules(workflow.getId(), organizationId);
            logger.info("[ScheduleSync] No pinned version for workflow {}, disabled all schedules",
                workflow.getId());
            return;
        }

        // Load the pinned version's plan
        WorkflowPlan pinnedPlan = loadPinnedPlan(workflow.getId(), pinnedVersion);
        if (pinnedPlan == null) {
            logger.warn("[ScheduleSync] Could not load plan for pinned version {} of workflow {}, disabling schedules",
                pinnedVersion, workflow.getId());
            disableAllSchedules(workflow.getId(), organizationId);
            return;
        }

        // Sync from the pinned plan
        syncFromPlan(workflow, pinnedPlan);

        // Re-arm any schedules that were SUSPENDED_UNPINNED on a previous unpin.
        // Symmetric counterpart of {@link #disableAllSchedules}: without this, the
        // unpin-side suspendSchedule call leaves rows at state=SUSPENDED_UNPINNED /
        // enabled=false, and the standalone update path here only refreshes
        // workflow_id/cron - never the lifecycle state. The next ScheduleExecutorService
        // tick filters on enabled=true, so the schedule never fires after a re-pin.
        // armSchedule is idempotent on already-ACTIVE rows (state stays ACTIVE,
        // enabled stays true). Same broad-rearm pattern as
        // WorkflowResumeService.reactivateWorkflow - the dispatch hot path no longer
        // auto-disables, so the executor remains the authoritative skip-this-tick guard.
        try {
            if (hasText(organizationId)) {
                triggerClient.enableSchedulesByWorkflow(workflow.getId(), organizationId);
            } else {
                triggerClient.enableSchedulesByWorkflow(workflow.getId());
            }
        } catch (Exception e) {
            logger.warn("[ScheduleSync] Failed to re-arm schedules for workflow {}: {}",
                workflow.getId(), e.getMessage());
        }
    }

    /**
     * Acquire a per-workflow Postgres advisory lock for the duration of the current
     * transaction. Auto-released on commit/rollback. Same {@code hashtext} key pattern
     * as {@code WorkflowPinService.acquirePinLock}, namespaced with {@code "schedule-sync:"}
     * so it never collides with the pin lock.
     *
     * <p>Caller must already hold a Spring-managed transaction (REQUIRED propagation
     * inherits from the @Transactional public entry point).
     *
     * <p>{@code entityManager} is null in unit tests (Mockito + @PersistenceContext is
     * not wired) - skip the lock there. The test path never has concurrent callers.
     */
    private void acquireWorkflowAdvisoryLock(UUID workflowId) {
        if (entityManager == null) return;
        entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(hashtext(:key))")
            .setParameter("key", "schedule-sync:" + workflowId)
            .getSingleResult();
    }

    /**
     * Syncs schedules from a specific plan. Creates/updates schedule triggers
     * and deletes orphans not present in this plan.
     *
     * <p><b>Package-private intentionally:</b> the concurrency guard
     * ({@code @Transactional} + per-workflow advisory lock) lives on the PUBLIC
     * entry point {@link #syncFromPinnedVersion}
     * - placing it here would be a no-op because Spring ignores @Transactional on
     * non-public methods AND self-invocations bypass the proxy. Callers from
     * outside the class go through one of those public entry points; the
     * single in-class caller ({@code syncFromPinnedVersion}) already holds the
     * tx + lock when it reaches this method.
     */
    void syncFromPlan(WorkflowEntity workflow, WorkflowPlan plan) {
        // Distinguish "plan unparseable / corrupted" from "plan parsed OK but has no
        // schedule triggers" - only the second case is allowed to delete all schedules.
        // Without this guard, a corrupted plan map (missing 'triggers' field) would silently
        // wipe every schedule for the workflow. (Defensive guard 2026-04-29 hardening.)
        if (plan == null || plan.getTriggers() == null) {
            logger.warn("[ScheduleSync] Refusing to cleanup schedules - plan or triggers list is null (workflow {})",
                    workflow.getId());
            return;
        }

        List<String> currentTriggerIds = new ArrayList<>();
        for (Trigger trigger : plan.getTriggers()) {
            if (TRIGGER_TYPE_SCHEDULE.equalsIgnoreCase(trigger.type())) {
                String triggerId = trigger.getNormalizedKey();
                currentTriggerIds.add(triggerId);
                syncSingleSchedule(workflow, triggerId, trigger);
            }
        }

        // Delete orphan schedules (triggers removed from the plan)
        cleanupOrphanSchedules(workflow.getId(), currentTriggerIds, workflowOrganizationId(workflow));

        if (!currentTriggerIds.isEmpty()) {
            logger.info("[ScheduleSync] Synced {} schedule(s) for workflow {} from plan",
                currentTriggerIds.size(), workflow.getId());
        }
    }

    /**
     * Counts schedule triggers in the workflow's pinned plan. Used by
     * {@code WorkflowResumeService.reactivateScheduleTriggers} to disambiguate
     * "0 schedule rows is expected (webhook/chat-only plan)" from "0 schedule
     * rows is a silent partial-failure (sync recreated some but not all)".
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code OptionalInt.empty()} when the workflow is not pinned, has no
     *       loadable pinned plan, or the plan parse fails - caller should
     *       fall back to row-count-only heuristics.</li>
     *   <li>{@code OptionalInt.of(N)} where N >= 0 is the count of schedule
     *       triggers declared by the pinned plan.</li>
     * </ul>
     */
    public java.util.OptionalInt countScheduleTriggersInPinnedPlan(WorkflowEntity workflow) {
        Integer pinnedVersion = workflow.getPinnedVersion();
        if (pinnedVersion == null) return java.util.OptionalInt.empty();
        WorkflowPlan plan = loadPinnedPlan(workflow.getId(), pinnedVersion);
        if (plan == null || plan.getTriggers() == null) return java.util.OptionalInt.empty();
        int count = 0;
        for (Trigger t : plan.getTriggers()) {
            if (TRIGGER_TYPE_SCHEDULE.equalsIgnoreCase(t.type())) count++;
        }
        return java.util.OptionalInt.of(count);
    }

    /**
     * Loads the plan for a specific pinned version.
     */
    WorkflowPlan loadPinnedPlan(UUID workflowId, int pinnedVersion) {
        if (versionService == null) {
            logger.warn("[ScheduleSync] WorkflowPlanVersionService not available");
            return null;
        }
        try {
            Optional<WorkflowPlanVersionEntity> versionOpt =
                versionService.getVersion(workflowId, pinnedVersion);
            if (versionOpt.isEmpty()) {
                logger.warn("[ScheduleSync] Pinned version {} not found for workflow {}",
                    pinnedVersion, workflowId);
                return null;
            }
            Map<String, Object> planMap = versionOpt.get().getPlan();
            if (planMap == null) {
                return null;
            }
            return WorkflowPlan.fromMap(planMap, null);
        } catch (Exception e) {
            logger.error("[ScheduleSync] Failed to load pinned plan v{} for workflow {}: {}",
                pinnedVersion, workflowId, e.getMessage());
            return null;
        }
    }

    /**
     * Sync a single schedule trigger via trigger-service.
     */
    private void syncSingleSchedule(WorkflowEntity workflow, String triggerId, Trigger trigger) {
        ScheduleConfig config = extractScheduleConfig(trigger);

        // Check if this trigger references a standalone schedule
        Map<String, Object> params = trigger.params();
        Object scheduleIdObj = params != null ? params.get("scheduleId") : null;
        if (scheduleIdObj != null) {
            try {
                UUID scheduleId = UUID.fromString(scheduleIdObj.toString());
                com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest updateReq =
                        new com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest(
                                null, null, config.cron(), config.timezone(), config.maxExecutions(), true, null, null);
                // Update the config (cron/timezone/maxExec) on the row by id.
                triggerClient.updateStandaloneScheduleStrict(
                        workflow.getTenantId(), scheduleId, updateReq);
                // ADOPT the standalone schedule onto THIS workflow: set workflow_id + trigger_id.
                // A schedule fires only when BOTH are present (findDueForUpdate needs workflow_id;
                // the executor needs a non-blank triggerId). Without adoption a builder-created
                // standalone schedule (workflow_id NULL, trigger_id '') is never linked → never
                // fires, isn't counted (N/9999), and is reaped after 24h.
                //
                // Hijack-safe (the F4 PUB-HIJACK class stays closed): the original rebind rewrote
                // workflow_id UNCONDITIONALLY (value→value). This call is guarded NULL→value ONLY
                // server-side (assertWorkflowReferenceMutationAllowed) + V206 at the DB + an
                // org-scoped finder, and PlanStripUtils strips scheduleId from every cloned/acquired
                // plan - so a clone can never carry a foreign scheduleId to claim. Idempotent on a
                // row already owned by THIS workflow; best-effort (logged, not fatal) otherwise.
                triggerClient.updateScheduleWorkflowReferenceStrict(
                        workflow.getTenantId(), workflowOrganizationId(workflow), scheduleId,
                        workflow.getId(), triggerId, workflow.getName());
                logger.info("[ScheduleSync] Updated + adopted standalone schedule {} (workflow {} trigger {})",
                        scheduleId, workflow.getId(), triggerId);
                return;
            } catch (TriggerClientException e) {
                if (e.isNotFound()) {
                    // Phantom standalone row - safe to fall through and let the attached
                    // upsert recreate a working row keyed by (workflowId, triggerId).
                    logger.warn("[ScheduleSync] Standalone schedule {} not found for workflow {} - falling back to attached upsert",
                            scheduleIdObj, workflow.getId());
                } else {
                    // Transient (5xx/timeout/transport) - DO NOT recreate, DO NOT delete.
                    // Skip this trigger entirely; next sync cycle will retry.
                    logger.error("[ScheduleSync] Transient failure linking standalone schedule {} for workflow {} ({}): {} - skipping sync to preserve state",
                            scheduleIdObj, workflow.getId(), e.getKind(), e.getMessage());
                    return;
                }
            } catch (IllegalArgumentException e) {
                // Malformed UUID in plan - log and fall through to attached path
                logger.warn("[ScheduleSync] Invalid scheduleId {} in plan for workflow {}: {}, falling back to create",
                        scheduleIdObj, workflow.getId(), e.getMessage());
            }
        }

        try {
            ScheduleCreateRequest request = new ScheduleCreateRequest(
                    config.cron(), config.timezone(), config.maxExecutions(),
                    config.enabled(), null);

            String name = trigger.label() != null ? trigger.label() : triggerId;
            // Thread workflow.getOrganizationId() so the schedule row carries the
            // workflow's workspace tag. Without this, sync from a pinned plan would
            // leave organization_id NULL and the schedule disappears from the bell
            // Triggers tab even though the workflow row itself is correctly stamped.
            String organizationId = workflowOrganizationId(workflow);
            ScheduledExecutionDto result = triggerClient.createOrUpdateSchedule(
                    workflow.getId(), triggerId, workflow.getTenantId(),
                    organizationId, request,
                    name, workflow.getName());

            if (result != null) {
                logger.info("[ScheduleSync] Synced schedule for workflow {} trigger {}: id={}",
                        workflow.getId(), triggerId, result.getId());
            } else {
                logger.warn("[ScheduleSync] createOrUpdateSchedule returned null for workflow {} trigger {} - next sync will retry",
                        workflow.getId(), triggerId);
            }
        } catch (Exception e) {
            logger.warn("[ScheduleSync] Failed to sync schedule for workflow {} trigger {}: {}",
                workflow.getId(), triggerId, e.getMessage());
        }
    }

    // NOTE: cleanupDuplicateRows + bestEffortCollapseRows were DELETED 2026-05-13.
    // Reason: V60 (UNIQUE workflow_id, trigger_id) + V136 (UNIQUE tenant_id, source_node_id)
    // physically prevent duplicate rows. The helpers were dead defense - they only fired
    // on a state the DB constraints already forbid, and they were the LAST remaining
    // direct callers of triggerClient.deleteScheduleById from non-reaper code (audit C
    // tech debt #8). Removal aligns with the v5 design intent and makes the only
    // delete path go through StandaloneTriggerReaperService.

    /**
     * Suspends orphan schedules - triggers no longer in the active plan.
     *
     * <p>v5 change: orphans are now SUSPENDED (state=SUSPENDED_NO_RUN, reason=PLAN_TRIGGER_REMOVED)
     * instead of physically DELETEd. The previous hard-delete pattern was
     * unrecoverable and lost forensic state (execution_count, last_execution_at,
     * the standalone UUID stable reference). With suspend, a subsequent plan
     * edit that re-adds the trigger will re-arm the same row via the existing
     * {@code armSchedule} path (accepts SUSPENDED_*, refuses ARCHIVED only).
     *
     * <p>{@code transitionSchedule.log()} (TriggerLifecycleManager) is no-op when
     * from==to (idempotency guard), so re-suspending an already-suspended row is
     * a free operation - no audit-log bloat even without a state filter on the
     * reader query.
     */
    private void cleanupOrphanSchedules(UUID workflowId, List<String> currentTriggerIds, String organizationId) {
        List<ScheduledExecutionDto> schedules = hasText(organizationId)
                ? triggerClient.getSchedulesByWorkflow(workflowId, organizationId)
                : triggerClient.getSchedulesByWorkflow(workflowId);
        if (schedules == null) return;
        for (ScheduledExecutionDto schedule : schedules) {
            if (currentTriggerIds != null && currentTriggerIds.contains(schedule.getTriggerId())) {
                continue;  // still declared in plan - leave alone
            }
            try {
                triggerClient.suspendSchedule(schedule.getId(), "PLAN_TRIGGER_REMOVED");
                logger.info("[ScheduleSync] Suspended orphan schedule {} for trigger {} (removed from pinned plan)",
                        schedule.getId(), schedule.getTriggerId());
            } catch (Exception e) {
                logger.warn("[ScheduleSync] Failed to suspend orphan schedule {} for trigger {}: {}",
                        schedule.getId(), schedule.getTriggerId(), e.getMessage());
            }
        }
    }

    /**
     * Round-7 PR5: suspends (not archives) all schedules for a workflow when it's unpinned.
     * Schedules move to {@code SUSPENDED_UNPINNED} state - preserves the row + cron config
     * so a subsequent re-pin event can re-arm them via {@code TriggerLifecycleManager.armSchedule}
     * without re-creating the row. Replaces the legacy hard {@code disableSchedule} call
     * that lost recoverability and conflicted with the new state machine (PR2).
     */
    private void disableAllSchedules(UUID workflowId, String organizationId) {
        try {
            List<ScheduledExecutionDto> schedules = hasText(organizationId)
                    ? triggerClient.getSchedulesByWorkflow(workflowId, organizationId)
                    : triggerClient.getSchedulesByWorkflow(workflowId);
            for (ScheduledExecutionDto schedule : schedules) {
                triggerClient.suspendSchedule(schedule.getId(), "WORKFLOW_UNPINNED");
            }
        } catch (Exception e) {
            logger.warn("[ScheduleSync] Failed to suspend schedules for workflow {}: {}",
                workflowId, e.getMessage());
        }
    }

    /**
     * Extracts schedule configuration from trigger params.
     */
    private ScheduleConfig extractScheduleConfig(Trigger trigger) {
        Map<String, Object> params = trigger.params();
        if (params == null) {
            params = Map.of();
        }

        String cron = (String) params.getOrDefault("cron", DEFAULT_CRON);
        String timezone = (String) params.getOrDefault("timezone", DEFAULT_TIMEZONE);
        Integer maxExecutions = params.get("maxExecutions") != null
            ? ((Number) params.get("maxExecutions")).intValue()
            : null;
        boolean enabled = Boolean.TRUE.equals(params.getOrDefault("enabled", true));

        return new ScheduleConfig(cron, timezone, maxExecutions, enabled);
    }

    private static String workflowOrganizationId(WorkflowEntity workflow) {
        if (workflow != null && workflow.getOrganizationId() != null
                && !workflow.getOrganizationId().isBlank()) {
            return workflow.getOrganizationId();
        }
        return TenantResolver.currentRequestOrganizationId();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ScheduleConfig(
        String cron,
        String timezone,
        Integer maxExecutions,
        boolean enabled
    ) {}
}
