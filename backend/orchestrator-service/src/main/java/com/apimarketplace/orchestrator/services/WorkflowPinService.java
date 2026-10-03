package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.services.persistence.ScheduleSyncService;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>The pin needs a production run at that version: one in a TRUSTED status
 *       ({@code COMPLETED}, {@code WAITING_TRIGGER}, {@code RUNNING}, {@code PAUSED})
 *       is elected, and when none exists the pin <b>creates</b> it - see
 *       {@link #provisionProductionRun}.</li>
 *   <li>On change, the chosen run is recorded as {@code production_run_id} and
 *       all production triggers re-sync from the newly pinned plan.</li>
 *   <li>Unpin (version=null) clears both {@code pinned_version} and
 *       {@code production_run_id}; the trigger sync layer suspends the rows.</li>
 *   <li>The whole transition is wrapped in
 *       {@code pg_advisory_xact_lock(hashtext('trigger:pin:'+workflowId))} so two
 *       admins re-pinning the same workflow within 100ms serialize cleanly (AC10).</li>
 * </ul>
 *
 * <p><b>Why the pin provisions its own run (2026-08-25).</b> Until now a version with no
 * run was refused outright ({@code NoSuccessfulRun}, "start a run with this version
 * first"), on the stated grounds that it "prevents pinning to an untested version". It
 * did not: every trigger type is reusable, so {@code execute} on a triggerable workflow
 * parks a run in {@code WAITING_TRIGGER} without traversing a single node, and
 * {@code WAITING_TRIGGER} is TRUSTED - an empty epoch-0 run satisfied the check. What the
 * refusal really protected is the invariant that <b>a pin must designate a run</b>: a
 * trigger fire opens an epoch on an existing run, it never opens a run, so a pin with a
 * null {@code production_run_id} arms triggers that resolve
 * {@code NO_PRODUCTION_RUN} and skip forever while the UI reads "Live". That invariant is
 * better satisfied than refused, so the pin now mints the run. It costs one
 * {@code workflow_runs} row: nothing executes, no credits are consumed (credits are
 * charged per node traversed).
 *
 * <p><b>What provisioning does NOT cover.</b> It fills the "no run at all" hole only.
 * A version whose newest trusted run is {@code COMPLETED} still elects that run, and a
 * {@code COMPLETED} FK makes {@code ProductionRunResolver.resolveFkFirst} resolve empty
 * for good (COMPLETED means the user deliberately stopped, so it is exempt from the
 * corrupt-FK heal by design). Same dead-schedule symptom, reached through a different
 * door, and unchanged by this work: minting a rival run there would silently undo a
 * deliberate stop, which is a separate decision, not a detail of this one.
 *
 * <p><b>Costs a provisioning pin carries.</b> It runs inside the pin's transaction and
 * advisory lock, so that window now also spans what {@code createExecution} does
 * synchronously: an auth-service display-name lookup, an interface-service template
 * snapshot, and a schedule sync. Two concurrent pins on the same workflow still
 * serialize correctly, they just queue behind those round-trips. It also increments the
 * {@code workflows started} metric, which a pin did not use to touch - the run really is
 * created, it simply never runs.
 */
@Slf4j
@Service
public class WorkflowPinService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowPlanVersionService versionService;
    private final PinAwareTriggerSyncService triggerSyncService;
    private final EntityManager entityManager;
    private final WorkflowExecutionService executionService;
    private final TriggerTypeDetector triggerTypeDetector;
    private final ScheduleSyncService scheduleSyncService;

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

    /**
     * TRUSTED minus COMPLETED - the statuses a run can still FIRE from. Rearm prefers
     * these (see {@link #rearm}); COMPLETED stays a valid last resort.
     */
    private static final List<RunStatus> LIVE_TRUSTED_STATUSES = List.of(
        RunStatus.WAITING_TRIGGER,
        RunStatus.RUNNING,
        RunStatus.PAUSED
    );

    /**
     * The single reason string a refused pin reports. It is read by an MCP agent, which
     * has no shell, no logs and no source, and it is also rendered verbatim in the UI -
     * so it describes the STATE, never an exception message or a classname
     * (the project docs). The technical cause goes to the log instead.
     */
    public static final String PROVISIONING_FAILED_REASON =
        "its production run could not be prepared";

    /** Optional: without it an unpinned workflow's incident closes by itself after a week, as before. */
    private NotificationDeliveryService notificationDelivery;

    @Autowired(required = false)
    public void setNotificationDelivery(NotificationDeliveryService notificationDelivery) {
        this.notificationDelivery = notificationDelivery;
    }

    /**
     * @param executionService  used to mint the production run when a pinned version has
     *                          none. {@code @Lazy} because the execution stack is large and
     *                          reaches this service back through {@code ProductionRunResolver};
     *                          the proxy keeps that from becoming a startup cycle. It is
     *                          deliberately REQUIRED, not {@code required = false}: an absent
     *                          bean must fail startup loudly rather than turn provisioning
     *                          into a silent no-op that only shows up as a dead schedule.
     * @param scheduleSyncService deliberately held ALONGSIDE {@code triggerSyncService},
     *                          which wraps it. Do not "simplify" this away: the refusal
     *                          repair needs schedules-only granularity, and neither wrapper
     *                          entry point offers it: one routes a null pin into a full
     *                          teardown that hard-deletes the workflow's webhook tokens, the
     *                          other still syncs webhooks, chat, form and datasource.
     */
    public WorkflowPinService(WorkflowRepository workflowRepository,
                              WorkflowRunRepository workflowRunRepository,
                              WorkflowPlanVersionService versionService,
                              EntityManager entityManager,
                              @Lazy WorkflowExecutionService executionService,
                              TriggerTypeDetector triggerTypeDetector,
                              @Autowired(required = false) PinAwareTriggerSyncService triggerSyncService,
                              @Autowired(required = false) ScheduleSyncService scheduleSyncService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.versionService = versionService;
        this.entityManager = entityManager;
        this.executionService = executionService;
        this.triggerTypeDetector = triggerTypeDetector;
        this.triggerSyncService = triggerSyncService;
        this.scheduleSyncService = scheduleSyncService;
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

        /**
         * The version has no production run and one could not be created, so pinning it
         * would arm triggers with nothing to fire. Renamed from {@code NoSuccessfulRun}
         * (2026-08-25) because the old name described the old rule: a missing run is now
         * provisioned, and this result means the provisioning itself refused or failed.
         *
         * @param version the version that was being pinned
         * @param reason  human-readable cause, safe to show to a user or an agent
         */
        record ProductionRunUnavailable(int version, String reason) implements PinResult {}
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
                // Parsing a malformed stored plan throws, so it shares the guard with
                // provisioning: either way the pin is refused, never left half-done.
                WorkflowPlan versionPlan = null;
                try {
                    versionPlan = parseVersionPlan(workflow, versionOpt.get());
                    // Gate on the PARSED plan, the very object createExecution will hand to
                    // buildRunEntity. Asking TriggerTypeDetector's raw-Map overload instead
                    // would be a second predicate over a second representation: the two could
                    // disagree on a trigger the parser drops, and that disagreement shows up
                    // as a run stamped RUNNING that nothing ever starts.
                    if (versionPlan == null || !triggerTypeDetector.hasReusableTrigger(versionPlan)) {
                        // Nothing to fire, so nothing to point at. Pinning stays legal - the
                        // pin also selects the version for core:sub_workflow and for
                        // execute(version='pinned') - and production_run_id stays NULL, which
                        // no dispatcher reads for a triggerless plan. Refusing here would
                        // block a pin that harms nothing.
                        log.info("[WorkflowPinService] workflow {} v{} has no reusable trigger - "
                                + "pinning without a production run", workflowId, version);
                    } else {
                        runOpt = provisionProductionRun(workflow, version, versionPlan);
                        if (runOpt.isEmpty()) {
                            // recordWorkflowStart swallows its own failures, so an empty
                            // result here means the row was never written. No schedule
                            // repair is owed on THIS path: that sync runs after the run
                            // save, so an unwritten run means it never ran either. (A
                            // deferred-INSERT failure surfacing later throws instead, and
                            // lands in the catch below, which does repair.)
                            return new PinResult.ProductionRunUnavailable(version, PROVISIONING_FAILED_REASON);
                        }
                    }
                } catch (RuntimeException e) {
                    // The technical cause goes to the log and STOPS there. This reason
                    // reaches an MCP agent (which cannot read logs or classnames) and a
                    // user-facing toast, so it names the state, not the stacktrace.
                    log.error("[WorkflowPinService] could not provision a production run for "
                            + "workflow {} v{}: {}", workflowId, version, e.getMessage(), e);
                    resyncSchedulesAfterRefusal(workflow, versionPlan);
                    return new PinResult.ProductionRunUnavailable(version, PROVISIONING_FAILED_REASON);
                }
            }

            if (runOpt.isPresent()) {
                productionRun = runOpt.get();
                newProductionRunId = productionRun.getId();
                newProductionRunIdPublic = productionRun.getRunIdPublic();
            }
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

        // Unpinned: nothing of this workflow runs in production any more, so nothing is "still
        // failing". A re-pin is left alone: its next run closes or continues the incident.
        if (version == null && notificationDelivery != null) {
            notificationDelivery.onWorkflowStopped(workflowId);
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
     * <p>REQUIRES_NEW is load-bearing (round-4 audit, HIGH): the primary caller is
     * {@code RunTerminationListener}, a {@code @TransactionalEventListener(AFTER_COMMIT)}.
     * In that phase the terminating transaction's resources are still bound to the
     * thread but already committed, so a plain REQUIRED join would attach the FK write
     * to a dead transaction and it would NEVER be flushed - the rearm silently no-oped
     * on the normal termination path. REQUIRES_NEW suspends whatever is bound and
     * commits the rearm independently; the resolver's missed-rearm heal remains the
     * backstop, not the norm.
     *
     * @return {@code true} if the workflow has a production_run_id after rearm,
     *         {@code false} if no TRUSTED run survived (state SUSPENDED_NO_RUN
     *         should be applied by the caller).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
        // Prefer a LIVE run (round-4 audit): the plain newest-TRUSTED election could
        // pick a newer COMPLETED run over a live WAITING_TRIGGER one, converting this
        // FAILED/CANCELLED termination into a permanent deliberate-stop stall
        // (COMPLETED FK = the schedule resolves EMPTY forever). COMPLETED remains the
        // last resort so the pre-existing "COMPLETED survivor" semantics still hold
        // when no live run exists.
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                        workflowId, pinned, LIVE_TRUSTED_STATUSES);
        if (runOpt.isEmpty()) {
            // Round-5 audit (HIGH): before the COMPLETED fallback, check for a run
            // parked on a blocking signal (approval pending, wait timer). Such a run
            // is not electable NOW but becomes eligible again the moment its signal
            // resolves and it parks WAITING_TRIGGER. Electing a COMPLETED sibling
            // over it would freeze the FK on a deliberate-stop identity that nothing
            // ever revisits (COMPLETED is exempt from the resolver heal by design,
            // and this listener never fires for it again) - a permanent stall on a
            // routine approval workflow. Writing NULL instead hands DISPATCH to the
            // FK-null bootstrap scan, which serves the signal run once it parks
            // WAITING_TRIGGER; the FK itself stays NULL until the next pin, rearm,
            // or run-termination event re-points it (nothing writes the FK from the
            // scan path).
            boolean blockedLiveRunExists = workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            workflowId, pinned, List.of(RunStatus.AWAITING_SIGNAL))
                    .isPresent();
            if (!blockedLiveRunExists) {
                runOpt = workflowRunRepository
                        .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                                workflowId, pinned, TRUSTED_STATUSES);
            } else {
                log.info("[WorkflowPinService] rearm: workflow {} has a run AWAITING_SIGNAL at v{} - "
                        + "clearing production_run_id instead of electing a COMPLETED fallback; the "
                        + "FK-null scan serves that run once it parks WAITING_TRIGGER", workflowId, pinned);
            }
        }

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
     * Parse a stored version's frozen plan, or {@code null} when there is nothing to parse.
     *
     * <p>Only called on the provisioning path (no trusted run at the version), so an
     * elected-run pin pays nothing for it.
     */
    private WorkflowPlan parseVersionPlan(WorkflowEntity workflow, WorkflowPlanVersionEntity version) {
        Map<String, Object> planMap = version.getPlan();
        if (planMap == null || planMap.isEmpty()) {
            return null;
        }
        return WorkflowPlan.fromMap(new HashMap<>(planMap),
                workflow.getId().toString(), workflow.getTenantId());
    }

    /**
     * Mint the production run for {@code version}: a run frozen on that version's plan,
     * parked in {@code WAITING_TRIGGER}, waiting for its first fire.
     *
     * <p>Reuses {@code WorkflowExecutionService.createExecution}, the same primitive
     * {@code EditorRunResolver.findOrCreateRunForVersion} uses, so the run gets the
     * initialized state snapshot and the frozen interface templates that every other run
     * gets. It starts nothing: {@code createExecution} never calls
     * {@code startAsyncExecution}, and for a reusable-trigger plan the run is created
     * directly in {@code WAITING_TRIGGER}.
     *
     * <p>Two deliberate differences from an editor run: no {@code __editorRun__} marker
     * (this run IS production, and {@code EditorRunResolver} refuses to adopt it for
     * editor fires via the {@code production_run_id} FK), and no {@code __mockMode__}
     * (production fires never mock).
     *
     * <p>Runs inside the caller's transaction and under its advisory lock, so two
     * concurrent pins on the same workflow cannot both mint a run.
     *
     * <p>Expect TWO schedule syncs in the logs for a provisioning pin: {@code
     * recordWorkflowStart} runs one whenever it creates a run for a plan that has a
     * schedule, and the caller runs the authoritative one afterwards. The first is
     * redundant ONLY because this method pre-sets {@code pinned_version} around the call
     * - see the comment on that line. Without it the inner sync would read a NULL pin
     * and disable every schedule on the workflow.
     *
     * @return the created run, or empty when persistence silently declined to write it
     *         ({@code recordWorkflowStart} logs and swallows its own failures)
     */
    private Optional<WorkflowRunEntity> provisionProductionRun(WorkflowEntity workflow,
                                                               int version,
                                                               WorkflowPlan plan) {
        UUID workflowId = workflow.getId();

        // ── Everything createExecution writes to the WORKFLOW row, captured ──────────
        //
        // Creating a run is not a read-only act on the workflow. Down the stack,
        // WorkflowEntityResolverService.resolveWorkflowEntity re-loads this workflow by
        // id - which, inside one persistence context, hands back THIS managed instance -
        // and overwrites its plan and dataInputs with the ones the run is starting from;
        // recordWorkflowStart then stamps lastExecutedAt and updatedAt. Under dirty
        // checking those land in the database whether or not anyone calls save().
        //
        // For an execution that is what you want. For a pin it is data loss: pinning an
        // older version that has no run - a rollback, the single most likely reason a
        // pinned version has no run - would silently replace the user's current draft in
        // workflows.plan with the older version's plan, and blank dataInputs to the empty
        // map this method passes. So capture the row's own state and put it back.
        //
        // Restored in a finally: a failed provisioning must not leave the entity dirty
        // either, because pin() returns normally on that path and a normal return from a
        // @Transactional method COMMITS.
        // updatedAt is deliberately NOT captured: @PreUpdate re-stamps it on every dirty
        // UPDATE and a pin does update the row, so restoring it would promise a guarantee
        // that cannot hold - and a pin SHOULD bump it.
        Map<String, Object> planBefore = workflow.getPlan();
        Map<String, Object> dataInputsBefore = workflow.getDataInputs();
        Instant lastExecutedBefore = workflow.getLastExecutedAt();
        Integer pinnedVersionBefore = workflow.getPinnedVersion();

        WorkflowExecution execution;
        try {
            // Pre-set the pin for the duration of the call. recordWorkflowStart re-syncs
            // schedules whenever it creates a run for a plan that has one, and
            // ScheduleSyncService treats a NULL pinned_version as "disable every schedule
            // for this workflow". On a first-ever pin that would DISABLE the schedules
            // mid-pin, and the caller's repair sync is best-effort (it logs and returns
            // Success on failure) - so one trigger-service hiccup would leave the workflow
            // pinned, badged Live, with its schedules off. Syncing against the version we
            // are pinning is correct rather than destructive.
            workflow.setPinnedVersion(version);
            execution = executionService.createExecution(plan, new HashMap<>(), version);
        } finally {
            workflow.setPlan(planBefore);
            workflow.setDataInputs(dataInputsBefore);
            workflow.setLastExecutedAt(lastExecutedBefore);
            // Back to the PREVIOUS pin, not to null: a failed re-pin must leave production
            // on the version it was already serving.
            workflow.setPinnedVersion(pinnedVersionBefore);
        }

        Optional<WorkflowRunEntity> created = workflowRunRepository
                .findByRunIdPublic(execution.getRunId());
        // No org stamping needed here: ScopeGuard.isInStrictScope has already established
        // that an org-context caller can only reach a workflow carrying that same org, so
        // buildRunEntity's copy of workflow.organization_id is right by construction.
        created.ifPresent(run -> log.info(
                "[WorkflowPinService] provisioned production run {} for workflow {} v{} "
                        + "(status={}) - no node executed", run.getRunIdPublic(), workflowId,
                version, run.getStatus()));
        return created;
    }

    /**
     * Undo what the inner schedule sync did, after a refused provisioning pin.
     *
     * <p>{@code provisionProductionRun} pre-sets the pin so the sync inside
     * {@code recordWorkflowStart} does not read a NULL and disable everything. When
     * provisioning then fails, that sync may already have armed schedules from the very
     * version being refused, and {@code pin()} returns before its own authoritative sync.
     * The {@code finally} has restored the previous pin, so re-syncing SCHEDULES from the
     * workflow puts them back: disabled when it was unpinned, re-armed from the previous
     * version when it was pinned.
     *
     * <p><b>Schedules, and nothing else.</b> The obvious-looking
     * {@code syncAllTriggersFromPinnedVersion} is the wrong instrument: on a restored NULL
     * pin it routes into {@code disableAllTriggers}, whose orphan-token cleanup passes an
     * EMPTY keep-list and therefore hard-DELETES every webhook token of the workflow -
     * killing URLs already handed to third parties. A refused pin is not an unpin: nothing
     * changed, and the unpinned lane deliberately keeps webhook/chat/form endpoints synced
     * from draft. Only schedules were touched, so only schedules are repaired.
     *
     * <p>Gated on the version's plan actually having a schedule, because that is the same
     * predicate {@code recordWorkflowStart} uses to decide whether to sync at all: without
     * it nothing was armed, and there is nothing to undo. Most refusals never get that far
     * ({@code parseVersionPlan}, the markup validator, the execution-graph cache all throw
     * earlier), and repairing what was never touched is how a fix becomes a defect.
     *
     * <p>Best-effort: a repair failure must not turn a refusal into a 500, and the next
     * pin or save re-syncs.
     */
    private void resyncSchedulesAfterRefusal(WorkflowEntity workflow, WorkflowPlan versionPlan) {
        if (scheduleSyncService == null || versionPlan == null
                || !scheduleSyncService.hasScheduleTrigger(versionPlan)) {
            return;
        }
        try {
            scheduleSyncService.syncFromPinnedVersion(workflow);
        } catch (Exception e) {
            log.warn("[WorkflowPinService] could not re-sync schedules after a refused pin on "
                    + "workflow {}: {}", workflow.getId(), e.getMessage());
        }
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
