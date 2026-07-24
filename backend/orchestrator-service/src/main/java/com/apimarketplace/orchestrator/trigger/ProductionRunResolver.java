package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Centralized resolver for production trigger run lookup.
 *
 * <p>Production triggers (schedule, webhook, form, chat, chained workflow) fire ONLY on the
 * workflow's pinned version. The status filter applied during lookup is governed by the
 * {@link RunSelectionPolicy} parameter - schedule wants WAITING_TRIGGER strictness, webhook
 * accepts the broader trusted set, etc. The previous unfiltered ordering by started_at DESC
 * was the root cause of the prod schedule auto-disable bug (a CANCELLED run shadowed a
 * valid older WAITING_TRIGGER run).
 *
 * <p>The companion defense-in-depth check
 * ({@link #isAllowedForProduction(WorkflowRunEntity, WorkflowEntity)}) is used by
 * {@code ReusableTriggerService.executeTriggerInternal} as a chokepoint after the dispatch
 * layer; it catches future dispatch services / forgotten code paths that might bypass this
 * resolver.
 */
@Service
public class ProductionRunResolver {

    private static final Logger logger = LoggerFactory.getLogger(ProductionRunResolver.class);

    /**
     * Trusted run statuses - runs in these states have a plan that can be safely
     * dispatched against. Mirrors {@code WorkflowPinService.pin()} validation.
     */
    private static final List<RunStatus> TRUSTED_STATUSES = List.of(
        RunStatus.COMPLETED,
        RunStatus.WAITING_TRIGGER,
        RunStatus.RUNNING,
        RunStatus.PAUSED
    );

    /**
     * Non-terminal statuses an ACTIVE step-by-step run can rest in. A SBS run is parked
     * in {@code PAUSED} while the user steps (see {@code reconcileSbsRunStatus}),
     * {@code RUNNING} while a node executes, {@code AWAITING_SIGNAL} on a blocking node,
     * and only reaches {@code WAITING_TRIGGER} once an epoch fully completes. It is
     * therefore almost never in {@code WAITING_TRIGGER} when a scheduled tick lands
     * mid-debug, which is why the strict {@link RunSelectionPolicy#LATEST_WAITING_TRIGGER}
     * schedule policy skips it. {@code COMPLETED} is intentionally excluded (a finished
     * run must not be re-fired by a schedule).
     */
    private static final List<RunStatus> SBS_ACTIVE_STATUSES = List.of(
        RunStatus.WAITING_TRIGGER,
        RunStatus.PAUSED,
        RunStatus.RUNNING,
        RunStatus.AWAITING_SIGNAL
    );

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;

    /**
     * Used by {@link #resolveFkFirst} to perform a MISSED rearm when the FK points at a
     * terminal (non-COMPLETED) run: the {@code WorkflowRunTerminatedEvent} that should have
     * re-pointed the FK is a one-shot AFTER_COMMIT event whose failures are swallowed, so
     * without this backstop a single missed event would leave the production lane EMPTY
     * forever. {@code @Lazy} + optional so partial contexts / plain unit constructions
     * without the bean degrade to the strict EMPTY behaviour instead of failing to wire.
     */
    @Autowired(required = false)
    @Lazy
    private WorkflowPinService pinService;

    public ProductionRunResolver(WorkflowRepository workflowRepository,
                                 WorkflowRunRepository runRepository) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
    }

    /** Test seam - field is {@code @Autowired(required=false)}, so plain constructions inject here. */
    void setPinService(WorkflowPinService pinService) {
        this.pinService = pinService;
    }

    /**
     * Result of a production run lookup.
     */
    public record Resolution(
        Optional<WorkflowRunEntity> run,
        Outcome outcome,
        String workflowName
    ) {
        public boolean isFound() { return outcome == Outcome.FOUND; }
        public boolean isNotPinned() { return outcome == Outcome.NOT_PINNED; }
        public boolean isWorkflowMissing() { return outcome == Outcome.WORKFLOW_MISSING; }
        public boolean isNoProductionRun() { return outcome == Outcome.NO_PRODUCTION_RUN; }
    }

    public enum Outcome {
        /** A run matching the policy was found. */
        FOUND,
        /** The workflow exists but has no pinned version - production triggers refused. */
        NOT_PINNED,
        /** The workflow itself was not found in the repository. */
        WORKFLOW_MISSING,
        /**
         * Workflow is pinned but no run satisfying the {@link RunSelectionPolicy} exists.
         * Renamed from {@code NO_MATCHING_RUN} (round-7 redesign) to make the contract
         * explicit: "no production run matches this policy at the pinned version".
         */
        NO_PRODUCTION_RUN
    }

    /**
     * Run-selection policy for the resolver.
     *
     * <p>Each trigger type picks the policy that matches its semantics:
     * <ul>
     *   <li>{@link #LATEST_TRUSTED} - webhook, form, chat, workflow-chain. Accepts any run
     *       in {@link ProductionRunResolver#TRUSTED_STATUSES} ordered by started_at DESC.
     *       This is the safe default that excludes CANCELLED/TIMEOUT/FAILED.</li>
     *   <li>{@link #LATEST_WAITING_TRIGGER} - schedule. The accumulation pattern requires
     *       a run in WAITING_TRIGGER specifically; the resolver returns
     *       {@link Outcome#NO_PRODUCTION_RUN} if only COMPLETED/RUNNING runs exist.</li>
     *   <li>{@link #BY_PRODUCTION_RUN_ID} - declared policy of the webhook/form/chat/chain
     *       strategies (PR4 direction). Since the production-identity unification it is an
     *       alias of {@link #LATEST_TRUSTED}: both route through the single FK-first rule
     *       ({@code resolveFkFirst}) with the TRUSTED status filter, so there is exactly one
     *       definition of "the production run" no matter which policy a caller declares.</li>
     * </ul>
     */
    public enum RunSelectionPolicy {
        BY_PRODUCTION_RUN_ID,
        LATEST_TRUSTED,
        LATEST_WAITING_TRIGGER
    }

    /**
     * Resolve the run that should receive a production trigger fire.
     *
     * <p>The default policy {@link RunSelectionPolicy#LATEST_TRUSTED} is the post-redesign
     * baseline that fixes the prod schedule auto-disable bug - it filters out CANCELLED
     * and other terminal statuses that would otherwise shadow a valid older run.
     *
     * <p><b>Transactional caution for future callers:</b> when the FK identity is corrupt
     * this method self-heals by calling {@code WorkflowPinService.rearm}, which runs in
     * {@code REQUIRES_NEW} and UPDATEs the {@code workflows} row. A caller that invokes
     * resolve() from inside a transaction which has ALREADY written that same
     * {@code workflows} row would self-deadlock (the inner transaction blocks on the
     * outer's row lock, which never releases). Every current call site is either
     * non-transactional or read-only on {@code workflows} before resolving - keep it
     * that way, or resolve BEFORE writing the row.
     *
     * @param workflowId workflow whose production run we want
     * @param policy run-selection policy; null defaults to {@link RunSelectionPolicy#LATEST_TRUSTED}
     * @return a {@link Resolution} describing what happened
     */
    public Resolution resolve(UUID workflowId, RunSelectionPolicy policy) {
        if (workflowId == null) {
            return new Resolution(Optional.empty(), Outcome.WORKFLOW_MISSING, null);
        }
        RunSelectionPolicy effectivePolicy = policy == null ? RunSelectionPolicy.LATEST_TRUSTED : policy;

        WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null) {
            logger.warn("[ProductionRunResolver] Workflow {} not found", workflowId);
            return new Resolution(Optional.empty(), Outcome.WORKFLOW_MISSING, null);
        }

        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null) {
            // DEBUG - this fires on every scheduled tick for every unpinned workflow
            // (~11/min in prod with 6 unpinned schedules). The outcome surfaces via the
            // returned Resolution and is recorded in the trigger_dispatch_total
            // Prometheus counter (verdict=REFUSE_NOT_PINNED) by the caller, so log
            // noise here is pure duplication - kept at DEBUG for ad-hoc tracing.
            logger.debug(
                "[ProductionRunResolver] Workflow {} ({}) has no pinned version - " +
                "production trigger refused. Pin a version to enable production execution.",
                workflowId, workflow.getName());
            return new Resolution(Optional.empty(), Outcome.NOT_PINNED, workflow.getName());
        }

        Optional<WorkflowRunEntity> runOpt = lookupByPolicy(workflow, pinned, effectivePolicy);

        if (runOpt.isEmpty()) {
            logger.warn(
                "[ProductionRunResolver] Workflow {} pinned to v{} - no run satisfies policy {}",
                workflowId, pinned, effectivePolicy);
            return new Resolution(Optional.empty(), Outcome.NO_PRODUCTION_RUN, workflow.getName());
        }

        return new Resolution(runOpt, Outcome.FOUND, workflow.getName());
    }

    /**
     * Resolve a STEP-BY-STEP run for a SCHEDULE fire when the strict
     * {@link RunSelectionPolicy#LATEST_WAITING_TRIGGER} policy found nothing.
     *
     * <p>Schedules use {@code LATEST_WAITING_TRIGGER} (the AUTOMATIC accumulation pattern),
     * but a step-by-step run rests in {@code PAUSED}/{@code RUNNING}/{@code AWAITING_SIGNAL}
     * while the user steps and only reaches {@code WAITING_TRIGGER} between fully-completed
     * epochs - so a scheduled SBS workflow would never get a new epoch while the user is
     * mid-step. This finds the pinned-version production run and returns it <b>only</b> when
     * it is in step-by-step mode, so AUTOMATIC {@code PAUSED}/{@code RUNNING} runs keep the
     * strict {@code WAITING_TRIGGER} contract (no accidental fire on a half-done automatic
     * run). Firing it routes through {@code ReusableTriggerService.executeTriggerInternal}'s
     * SBS branch, which closes the open epoch and opens a fresh one - exactly one new epoch
     * per tick, identical to a manual/webhook re-fire.
     *
     * @param workflowId workflow whose step-by-step run we want
     * @return the pinned SBS run to fire, or empty if the workflow is missing / not pinned /
     *         has no active step-by-step run
     */
    public Optional<WorkflowRunEntity> resolveStepByStepRun(UUID workflowId) {
        if (workflowId == null) {
            return Optional.empty();
        }
        WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null || workflow.getPinnedVersion() == null) {
            return Optional.empty();
        }
        // Same FK-decides rule as the LATEST_* policies. ScheduleExecutorService
        // FIRES the run this returns (fresh epoch, bypassing the terminal-status
        // guard), so an editor SBS run winning the started_at scan would be executed
        // as production, mock overrides included. Only the production run itself,
        // parked in an SBS-active status and genuinely step-by-step, may take the
        // tick; the scan survives solely for FK-null bootstrap and corrupt-FK
        // self-healing.
        return resolveFkFirst(workflow, workflow.getPinnedVersion(),
            run -> SBS_ACTIVE_STATUSES.contains(run.getStatus()) && run.isStepByStepMode(),
            () -> runRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                    workflowId, workflow.getPinnedVersion(), SBS_ACTIVE_STATUSES)
                .filter(WorkflowRunEntity::isStepByStepMode));
    }

    private Optional<WorkflowRunEntity> lookupByPolicy(WorkflowEntity workflow,
                                                       Integer pinned,
                                                       RunSelectionPolicy policy) {
        UUID workflowId = workflow.getId();
        return switch (policy) {
            // Showcase clones share workflow_id + plan_version + status with the
            // source run; production triggers (schedule, webhook, …) must never
            // fire on them - they're inert frozen snapshots. The Production*
            // repo variants filter source != 'showcase' AND runIdPublic NOT LIKE
            // 'showcase_%' (defense in depth: RunCloneService stamps both).
            // FK FIRST, and the FK DECIDES. The scan orders by started_at DESC, so
            // any NEWER run at the pinned version out-sorts the real production run -
            // and editor/agent fires do create runs there (EditorRunResolver mints a
            // separate run rather than adopting production, and a reusable trigger
            // parks it in WAITING_TRIGGER). Two rules follow:
            //   1. A live, version-true FK run answers the policy: eligible status ->
            //      fire it; ineligible status -> EMPTY (production exists but is not
            //      fireable this tick - e.g. RUNNING mid-epoch for the strict
            //      schedule policy). Falling back to the scan here would hand the
            //      tick to a same-version rival, which post-adoption-fix is by
            //      construction an editor/agent iteration run: mocked state fires as
            //      production while the real run stops accumulating epochs.
            //   2. The scan remains ONLY for identity-less states: FK null (legacy
            //      pre-FK pin / rearm cleared it) or a corrupt FK (row purged,
            //      showcase clone, stale plan_version), each WARN-logged. That
            //      preserves bootstrap and the existing self-healing semantics
            //      without re-opening the rival window for healthy workflows.
            case LATEST_WAITING_TRIGGER -> resolveFkFirst(workflow, pinned,
                run -> run.getStatus() == RunStatus.WAITING_TRIGGER,
                () -> runRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                        workflowId, pinned, RunStatus.WAITING_TRIGGER));
            case LATEST_TRUSTED -> resolveFkFirst(workflow, pinned,
                run -> TRUSTED_STATUSES.contains(run.getStatus()),
                () -> runRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                        workflowId, pinned, TRUSTED_STATUSES));
            // PR4 (round-7): O(1) lookup via the FK column maintained by
            // PinTransaction + RunTerminationListener. Routed through resolveFkFirst
            // so there is exactly ONE production-identity rule: FK null / purged row /
            // showcase clone / stale plan_version / unrearmed terminal → LATEST_TRUSTED
            // scan fallback; otherwise the FK run answers WHATEVER its live status is
            // (the always-true filter: this policy returns THE production run row,
            // status included - callers apply their own eligibility, e.g. the
            // dispatcher's terminal-status verdict for a deliberate COMPLETED stop).
            case BY_PRODUCTION_RUN_ID -> resolveFkFirst(workflow, pinned,
                run -> true,
                () -> runRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                        workflowId, pinned, TRUSTED_STATUSES));
        };
    }

    /**
     * The single production-identity rule shared by the {@code LATEST_*} policies
     * and {@link #resolveStepByStepRun}: {@code workflow.production_run_id} decides.
     *
     * <ul>
     *   <li>FK null → {@code scan} (bootstrap: legacy pre-FK pin, or rearm cleared
     *       it because no trusted run survived)</li>
     *   <li>CORRUPT identity → WARN + <b>rearm self-heal</b> ({@link #healCorruptFk}:
     *       FK re-pointed or cleared, then re-resolve). Four corrupt shapes: FK row
     *       missing (retention purge); showcase clone; stale {@code plan_version};
     *       and terminal-but-NOT-COMPLETED status (FAILED/CANCELLED/TIMEOUT/SKIPPED/
     *       PARTIAL_SUCCESS - {@code RunTerminationListener} rearms on exactly these,
     *       so still seeing one behind the FK means that one-shot AFTER_COMMIT rearm
     *       was lost). Healing via rearm instead of a bare scan keeps the FK-keyed
     *       guards (MockRunGate, plan-edit refusal, NotificationEmitter) aligned with
     *       whatever actually fires, and repairs the state instead of re-scanning
     *       forever. COMPLETED is deliberately NOT healed: it means the user stopped
     *       production on purpose (no auto-rearm, deliberate-stop semantics).</li>
     *   <li>FK run live and version-true, status satisfies the policy → return it</li>
     *   <li>FK run live and version-true, status FAILS the policy (a NON-terminal
     *       ineligible status - RUNNING/PAUSED/AWAITING_SIGNAL mid-cycle - or a
     *       deliberate COMPLETED stop) → <b>empty, never the scan</b>. Production
     *       exists but is not fireable this tick; any other run at the pinned
     *       version is an editor/agent iteration run (adoption is refused since
     *       2026-07-20), and handing it the tick is the hijack this rule exists
     *       to prevent.</li>
     * </ul>
     */
    private Optional<WorkflowRunEntity> resolveFkFirst(
            WorkflowEntity workflow, Integer pinned,
            java.util.function.Predicate<WorkflowRunEntity> statusFilter,
            java.util.function.Supplier<Optional<WorkflowRunEntity>> scan) {
        UUID prodRunId = workflow.getProductionRunId();
        if (prodRunId == null) {
            return scan.get();
        }
        Optional<WorkflowRunEntity> direct = runRepository.findById(prodRunId);
        if (direct.isEmpty()) {
            logger.warn("[ProductionRunResolver] Workflow {} production_run_id={} row is gone (purged?) - "
                    + "healing the corrupt FK via rearm", workflow.getId(), prodRunId);
            return healCorruptFk(workflow, prodRunId, pinned, statusFilter, scan);
        }
        WorkflowRunEntity run = direct.get();
        if (isShowcaseRun(run)) {
            logger.warn("[ProductionRunResolver] Workflow {} production_run_id={} is a showcase clone - "
                    + "healing the corrupt FK via rearm", workflow.getId(), prodRunId);
            return healCorruptFk(workflow, prodRunId, pinned, statusFilter, scan);
        }
        if (pinned != null && !pinned.equals(run.getPlanVersion())) {
            logger.warn("[ProductionRunResolver] Workflow {} production_run_id={} sits at plan_version={} "
                    + "but the pin is v{} - stale FK, healing via rearm",
                    workflow.getId(), prodRunId, run.getPlanVersion(), pinned);
            return healCorruptFk(workflow, prodRunId, pinned, statusFilter, scan);
        }
        RunStatus status = run.getStatus();
        if (status != null && status.isTerminal() && status != RunStatus.COMPLETED) {
            logger.warn("[ProductionRunResolver] Workflow {} production_run_id={} is terminal ({}) but was "
                    + "never rearmed (lost WorkflowRunTerminatedEvent?) - performing the missed rearm now",
                    workflow.getId(), prodRunId, status);
            return healCorruptFk(workflow, prodRunId, pinned, statusFilter, scan);
        }
        return statusFilter.test(run) ? direct : Optional.empty();
    }

    /**
     * Heals a CORRUPT production identity (purged FK row, showcase clone, stale
     * plan_version, or a terminal non-COMPLETED run whose rearm event was lost) by
     * performing the rearm the system owes, then resolving against the healed FK.
     * Why rearm instead of falling straight back to the scan: the scan's winner would
     * fire as production while {@code production_run_id} still pointed at the corrupt
     * identity, so every FK-keyed production guard would misclassify it - MockRunGate
     * would let a leftover {@code __mockMode__} mock the fire, the run-plan edit guard
     * would not protect it, and NotificationEmitter would suppress its notifications -
     * and nothing would ever repair the state. Rearm makes the same election a
     * {@code RunTerminationListener} call would have made (advisory-locked, idempotent,
     * newest live-preferred TRUSTED run at the pin or NULL), writes it to the FK, and
     * the recursive resolve then treats the elected run as real production.
     *
     * <p>The re-resolve terminates: after a rearm the FK is either NULL (scan/bootstrap
     * branch), or a live version-true TRUSTED run (no corrupt branch re-entered). If the
     * FK is somehow unchanged (rearm raced or failed), returns empty - the lane skips
     * this tick and retries on the next one rather than firing a rival. If the PIN moved
     * while the heal ran (concurrent re-pin), returns empty too: this resolve's status
     * filter and scan closures were built for the OLD pin, so continuing could fire an
     * old-plan-version run as production - the next tick resolves with fresh closures.
     *
     * <p>Without the pin service the scan fallback is used directly (same election, no
     * FK write). In a real Spring context the bean is always present ({@code @Lazy}
     * field injection, wiring proven by test); the null path exists ONLY for plain
     * unit constructions of this class - it is not a production degradation mode.
     */
    private Optional<WorkflowRunEntity> healCorruptFk(
            WorkflowEntity workflow, UUID corruptRunId, Integer pinned,
            java.util.function.Predicate<WorkflowRunEntity> statusFilter,
            java.util.function.Supplier<Optional<WorkflowRunEntity>> scan) {
        if (pinService == null) {
            return scan.get();
        }
        try {
            pinService.rearm(workflow.getId());
        } catch (Exception e) {
            logger.warn("[ProductionRunResolver] Corrupt-FK self-heal failed for workflow {}: {} - "
                    + "skipping this tick", workflow.getId(), e.getMessage());
            return Optional.empty();
        }
        WorkflowEntity fresh = workflowRepository.findById(workflow.getId()).orElse(null);
        if (fresh == null) {
            return Optional.empty();
        }
        if (!Objects.equals(fresh.getPinnedVersion(), pinned)) {
            logger.warn("[ProductionRunResolver] Workflow {} pin moved (v{} -> v{}) while the corrupt-FK "
                    + "self-heal ran - skipping this tick, the next one resolves against the new pin",
                    workflow.getId(), pinned, fresh.getPinnedVersion());
            return Optional.empty();
        }
        if (Objects.equals(fresh.getProductionRunId(), corruptRunId)) {
            logger.warn("[ProductionRunResolver] Workflow {} production_run_id={} unchanged after "
                    + "corrupt-FK self-heal - skipping this tick", workflow.getId(), corruptRunId);
            return Optional.empty();
        }
        return resolveFkFirst(fresh, pinned, statusFilter, scan);
    }

    private static boolean isShowcaseRun(WorkflowRunEntity run) {
        if ("showcase".equals(run.getSource())) return true;
        String publicId = run.getRunIdPublic();
        return publicId != null && publicId.startsWith("showcase_");
    }

    /**
     * Defense-in-depth check used by {@code ReusableTriggerService} after a run is
     * dequeued for execution. Returns true if the run's plan version matches the
     * workflow's pinned version, OR if the workflow has no pin (in which case the
     * chokepoint is not applicable - the run shouldn't have reached execution at all,
     * but we don't double-fail it here).
     *
     * <p>Editor runs are NOT checked by this method - the caller in
     * {@code ReusableTriggerService} skips the check via {@code isEditorRun(run)}.
     *
     * @return {@code true} if the run is allowed to execute under the production
     *         pin contract; {@code false} if the dispatch resolution leaked
     *         (e.g., a non-pinned-version run made it through)
     */
    public boolean isAllowedForProduction(WorkflowRunEntity run, WorkflowEntity workflow) {
        if (run == null || workflow == null) return false;
        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null) {
            // No pin = no production trigger should have routed here. Reject.
            return false;
        }
        return Objects.equals(run.getPlanVersion(), pinned);
    }
}
