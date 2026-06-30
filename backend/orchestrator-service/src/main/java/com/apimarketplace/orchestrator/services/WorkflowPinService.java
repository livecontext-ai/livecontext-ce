package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central pin/unpin orchestration. Used by both the REST controller (UI) and the
 * agent workflow tool (action='pin'/'unpin') so the validation rules and side effects
 * (trigger re-sync, production_run_id update) stay single-sourced.
 *
 * <p>Round-7 redesign (PR3): pin/unpin is now an atomic operation that updates BOTH
 * {@code workflows.pinned_version} AND {@code workflows.production_run_id} under the
 * same Postgres advisory lock. This eliminates the per-tick run lookup race that
 * caused the prod schedule auto-disable bug - the dispatcher no longer needs to
 * search by (workflow_id, plan_version) at every fire.
 *
 * <p>Pin semantics:
 * <ul>
 *   <li>Version must exist in history.</li>
 *   <li>Version must have a run in a TRUSTED status set
 *       ({@code COMPLETED}, {@code WAITING_TRIGGER}, {@code RUNNING}, {@code PAUSED}).</li>
 *   <li>On change, the chosen run is recorded as {@code production_run_id} and
 *       all production triggers re-sync from the newly pinned plan.</li>
 *   <li>Unpin (version=null) clears both {@code pinned_version} and
 *       {@code production_run_id}; the trigger sync layer suspends the rows.</li>
 *   <li>The whole transition is wrapped in
 *       {@code pg_advisory_xact_lock(hashtext('trigger:pin:'+workflowId))} so two
 *       admins re-pinning the same workflow within 100ms serialize cleanly (AC10).</li>
 * </ul>
 */
@Slf4j
@Service
public class WorkflowPinService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowPlanVersionService versionService;
    private final PinAwareTriggerSyncService triggerSyncService;
    private final EntityManager entityManager;

    /**
     * Trusted statuses considered "good enough" to be a workflow's production run.
     * Mirrors {@code ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED} from PR1.
     */
    private static final List<RunStatus> TRUSTED_STATUSES = List.of(
        RunStatus.COMPLETED,
        RunStatus.WAITING_TRIGGER,
        RunStatus.RUNNING,
        RunStatus.PAUSED
    );

    public WorkflowPinService(WorkflowRepository workflowRepository,
                              WorkflowRunRepository workflowRunRepository,
                              WorkflowPlanVersionService versionService,
                              EntityManager entityManager,
                              @Autowired(required = false) PinAwareTriggerSyncService triggerSyncService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.versionService = versionService;
        this.entityManager = entityManager;
        this.triggerSyncService = triggerSyncService;
    }

    public sealed interface PinResult {
        /**
         * @param pinnedVersion         the pinned version (null when unpinning)
         * @param productionRunIdPublic public runId of the production run that was selected as
         *                              the trusted source for this pin. {@code null} when
         *                              unpinning. Used by the frontend to auto-redirect to
         *                              {@code /run/{id}} so the user can watch the schedule
         *                              fire live (otherwise the builder edit URL doesn't
         *                              subscribe to the WS channel - see WorkflowModeContext).
         */
        record Success(Integer pinnedVersion, String productionRunIdPublic) implements PinResult {}
        record NotFound() implements PinResult {}
        record Forbidden() implements PinResult {}
        record VersionNotFound(int version) implements PinResult {}
        record NoSuccessfulRun(int version) implements PinResult {}
    }

    /**
     * Set the pinned version (or clear it by passing {@code null}).
     *
     * <p>Atomic transition wrapped in a Postgres advisory lock keyed by
     * {@code workflowId} so concurrent pin requests on the same workflow serialize.
     * Both {@code pinned_version} and {@code production_run_id} are updated in the
     * same transaction; any subsequent dispatcher tick observes a consistent state.
     *
     * @param workflowId workflow UUID
     * @param tenantId   caller tenant (ownership enforced)
     * @param version    positive version number to pin, or {@code null} to unpin
     */
    @Transactional
    public PinResult pin(UUID workflowId, String tenantId, Integer version) {
        return pin(workflowId, tenantId, null, version);
    }

    /**
     * Org-aware overload - caller must own the workflow OR be in the workflow's
     * org. Audit 2026-05-16: prior implementation was strict-tenant, breaking
     * the pin action for org teammates.
     */
    @Transactional
    public PinResult pin(UUID workflowId, String tenantId, String orgId, Integer version) {
        // Per-workflow advisory lock - released at transaction commit/rollback.
        // hashtext yields a stable int4 key; the namespace 'trigger:pin:' prefix
        // documents intent (collisions would be only between concurrent pin ops).
        acquirePinLock(workflowId);

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return new PinResult.NotFound();
        }
        WorkflowEntity workflow = workflowOpt.get();
        // Strict-isolation scope (2026-05-18, ScopeGuard alignment). Pin is a
        // mutation that flips the production version pointer - must respect
        // active workspace, not just ownership.
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                workflow.getTenantId(), workflow.getOrganizationId())) {
            return new PinResult.Forbidden();
        }

        UUID newProductionRunId = null;
        String newProductionRunIdPublic = null;
        WorkflowRunEntity productionRun = null;

        if (version != null) {
            Optional<WorkflowPlanVersionEntity> versionOpt = versionService.getVersion(workflowId, version);
            if (versionOpt.isEmpty()) {
                return new PinResult.VersionNotFound(version);
            }
            // Production-run lookup MUST exclude showcase clones (RunCloneService
            // creates them with the same workflow_id + plan_version + status, so a
            // naïve "latest by startedAt" lookup picks the clone - pinning then
            // freezes the schedule on an inert run that never progresses).
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            workflowId, version, TRUSTED_STATUSES);
            if (runOpt.isEmpty()) {
                return new PinResult.NoSuccessfulRun(version);
            }
            productionRun = runOpt.get();
            newProductionRunId = productionRun.getId();
            newProductionRunIdPublic = productionRun.getRunIdPublic();
        }

        backfillWorkflowOrganizationForPin(workflow, orgId, productionRun);
        workflow.setPinnedVersion(version);
        workflow.setProductionRunId(newProductionRunId);
        workflowRepository.save(workflow);

        log.info("[WorkflowPinService] workflow {} pinned version set to {} " +
                 "(production_run_id={}, tenant={})",
                workflowId,
                version != null ? "v" + version : "null (unpinned)",
                newProductionRunId,
                tenantId);

        if (triggerSyncService != null) {
            try {
                triggerSyncService.syncAllTriggersFromPinnedVersion(workflow);
            } catch (Exception e) {
                log.warn("[WorkflowPinService] trigger sync failed for workflow {}: {}",
                        workflowId, e.getMessage());
            }
        }

        return new PinResult.Success(version, newProductionRunIdPublic);
    }

    /**
     * Re-arm a workflow's production run after the current production run terminated
     * (CANCELLED/TIMEOUT/FAILED). Picks the most recent TRUSTED run at the pinned
     * version, or marks production_run_id NULL if none survives.
     *
     * <p>Called by {@code RunTerminationListener} when a production run reaches a
     * terminal status. Holds the same advisory lock as {@link #pin} so a concurrent
     * pin request cannot interleave.
     *
     * <p>Idempotent: calling rearm twice with no new termination event is a no-op
     * (the second call finds the same TRUSTED run and writes the same id).
     *
     * @return {@code true} if the workflow has a production_run_id after rearm,
     *         {@code false} if no TRUSTED run survived (state SUSPENDED_NO_RUN
     *         should be applied by the caller).
     */
    @Transactional
    public boolean rearm(UUID workflowId) {
        acquirePinLock(workflowId);

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            log.warn("[WorkflowPinService] rearm: workflow {} not found", workflowId);
            return false;
        }
        WorkflowEntity workflow = workflowOpt.get();
        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null) {
            log.debug("[WorkflowPinService] rearm: workflow {} has no pin, nothing to do",
                    workflowId);
            return false;
        }

        // Same showcase-exclusion rule as pin() - rearm must never elect a
        // frozen clone as the surviving production run.
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                        workflowId, pinned, TRUSTED_STATUSES);

        UUID newRunId = runOpt.map(WorkflowRunEntity::getId).orElse(null);
        workflow.setProductionRunId(newRunId);
        workflowRepository.save(workflow);

        log.info("[WorkflowPinService] rearm: workflow {} pinned v{} → production_run_id={}",
                workflowId, pinned, newRunId);

        if (triggerSyncService != null && newRunId != null) {
            try {
                triggerSyncService.syncAllTriggersFromPinnedVersion(workflow);
            } catch (Exception e) {
                log.warn("[WorkflowPinService] rearm: trigger sync failed for workflow {}: {}",
                        workflowId, e.getMessage());
            }
        }
        return newRunId != null;
    }

    /**
     * Acquire the per-workflow Postgres advisory lock for the duration of the current
     * transaction. {@code hashtext} returns a stable int4 from the namespace string;
     * the prefix is documentary only.
     */
    private void acquirePinLock(UUID workflowId) {
        entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(hashtext(:key))")
            .setParameter("key", "trigger:pin:" + workflowId)
            .getSingleResult();
    }

    private void backfillWorkflowOrganizationForPin(
            WorkflowEntity workflow,
            String requestOrgId,
            WorkflowRunEntity productionRun) {
        if (workflow.getOrganizationId() != null && !workflow.getOrganizationId().isBlank()) {
            return;
        }
        String runOrgId = productionRun != null ? productionRun.getOrganizationId() : null;
        String resolvedOrgId = hasText(requestOrgId) ? requestOrgId : runOrgId;
        if (!hasText(resolvedOrgId)) {
            return;
        }
        workflow.setOrganizationId(resolvedOrgId);
        log.info("[WorkflowPinService] Backfilled workflow {} organization_id from pin context",
                workflow.getId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
