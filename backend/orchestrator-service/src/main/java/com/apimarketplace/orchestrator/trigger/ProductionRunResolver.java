package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ProductionRunResolver(WorkflowRepository workflowRepository,
                                 WorkflowRunRepository runRepository) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
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
     *   <li>{@link #BY_PRODUCTION_RUN_ID} - reserved for the round-7+ redesign that adds
     *       a {@code workflow.production_run_id} FK. Falls back to {@link #LATEST_TRUSTED}
     *       in PR1 because the column does not exist yet.</li>
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
        return runRepository
            .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                workflowId, workflow.getPinnedVersion(), SBS_ACTIVE_STATUSES)
            .filter(WorkflowRunEntity::isStepByStepMode);
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
            case LATEST_WAITING_TRIGGER -> runRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                    workflowId, pinned, RunStatus.WAITING_TRIGGER);
            case LATEST_TRUSTED -> runRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                    workflowId, pinned, TRUSTED_STATUSES);
            case BY_PRODUCTION_RUN_ID -> {
                // PR4 (round-7): O(1) lookup via the FK column maintained by
                // PinTransaction + RunTerminationListener. Falls back to LATEST_TRUSTED
                // when the FK is NULL (workflow pre-bootstrap or rearm cleared the column
                // because no trusted run survived).
                UUID prodRunId = workflow.getProductionRunId();
                if (prodRunId != null) {
                    Optional<WorkflowRunEntity> direct = runRepository.findById(prodRunId);
                    if (direct.isPresent()) {
                        WorkflowRunEntity run = direct.get();
                        // Self-healing: if a legacy pin recorded a showcase clone as
                        // production_run_id (pre-fix), reject the FK and fall back to
                        // the showcase-excluding lookup so the schedule reattaches to
                        // a real run rather than firing on the inert clone forever.
                        if (isShowcaseRun(run)) {
                            logger.warn(
                                "[ProductionRunResolver] Workflow {} production_run_id={} is a showcase clone - " +
                                "ignoring FK and falling back to LATEST_TRUSTED policy",
                                workflowId, prodRunId);
                        } else {
                            yield direct;
                        }
                    } else {
                        // FK pointed at a row that no longer exists (deleted run) - fall back.
                        logger.warn(
                            "[ProductionRunResolver] Workflow {} production_run_id={} not found - " +
                            "falling back to LATEST_TRUSTED policy", workflowId, prodRunId);
                    }
                }
                yield runRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                        workflowId, pinned, TRUSTED_STATUSES);
            }
        };
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
