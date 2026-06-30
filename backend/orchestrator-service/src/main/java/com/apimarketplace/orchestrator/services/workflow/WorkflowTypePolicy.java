package com.apimarketplace.orchestrator.services.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;

/**
 * Encodes per-{@link WorkflowType} behavioural rules - the parts of workflow
 * lifecycle that diverge based on whether the workflow is a regular workflow
 * or an application clone.
 *
 * <p>This is a static utility (not a Spring bean) because the rules are pure
 * functions of the type - no I/O, no state. Callers consult it at the entry
 * boundary (controller → service) to decide whether the requested operation
 * is allowed for the given workflow.
 *
 * <p>Rationale: cancelling a run via the user-facing path (board paused
 * column, REST cancel) is a "stop the workflow's automation" intent for both
 * types. The original design (v3.5 §L3) limited trigger-suspension to
 * {@code APPLICATION} because applications are single-run by contract.
 * Extension (2026-05-13): {@code WORKFLOW} also suspends triggers on cancel
 * to fix the paused-zombie schedule bug - see
 * {@link #suspendsTriggersOnRunTermination(WorkflowType)} for the long-form
 * history. The {@link #allowsCancelRunOnly(WorkflowType)} predicate remains
 * asymmetric: {@code WORKFLOW} still permits the run-only cancel API surface
 * (independent of whether triggers get suspended as a side effect).
 *
 * <p>Two methods that map directly to design v3.5's named-method contract
 * ({@code cancelRunOnly} / {@code cancelWorkflowAndSuspendTriggers}):
 *
 * <ul>
 *   <li>{@link #allowsCancelRunOnly(WorkflowType)} - false for {@code APPLICATION}.
 *       A controller seeing this should reject {@code cancelRunOnly} with
 *       409 {@code OPERATION_NOT_ALLOWED_FOR_TYPE} and force the caller to
 *       use the trigger-suspending variant. {@code WORKFLOW} remains true:
 *       the per-run cancel REST surface is preserved.</li>
 *   <li>{@link #suspendsTriggersOnRunTermination(WorkflowType)} - true for
 *       BOTH {@code WORKFLOW} and {@code APPLICATION} since 2026-05-13.
 *       The cancel/pause path consults this and drives the
 *       trigger-suspension flow as part of the termination transaction.</li>
 * </ul>
 *
 * <p>If a third {@link WorkflowType} is added later, both methods must be
 * updated explicitly - there is no default fall-through. The exhaustive
 * {@code switch} surfaces a compile error so the rule stays visible.
 */
public final class WorkflowTypePolicy {

    private WorkflowTypePolicy() {}

    /**
     * @return {@code true} when the type allows a "cancel just this run, leave
     *         triggers alive" operation. {@code false} for types whose
     *         single-run contract makes that semantically incoherent (currently
     *         only {@link WorkflowType#APPLICATION}).
     */
    public static boolean allowsCancelRunOnly(WorkflowType type) {
        return switch (type) {
            case WORKFLOW    -> true;
            case APPLICATION -> false;
        };
    }

    /**
     * @return {@code true} when terminating a run on this type must also
     *         suspend the workflow's triggers. {@code true} for both
     *         {@link WorkflowType#APPLICATION} (single-run contract from v3.5)
     *         and {@link WorkflowType#WORKFLOW} (user-paused = zombie-trigger
     *         cleanup, fixed 2026-05-13).
     *
     * <p>History: prior to 2026-05-13 this returned {@code false} for
     * {@code WORKFLOW}. The asymmetry created a "paused zombie" - the user
     * drags a workflow card to the "paused" column → frontend calls
     * {@code cancelWorkflow} → run goes CANCELLED but the schedule stays
     * {@code state=ACTIVE}. The dispatcher then picks the schedule every
     * minute, the resolver returns {@code NO_PRODUCTION_RUN} (no
     * WAITING_TRIGGER run at the pinned version), {@code advanceSchedule}
     * is skipped, and the row produces a WARN log forever with a stale
     * {@code next_execution_at}. Flipping this to {@code true} routes the
     * cancel through {@code triggerClient.suspendSchedulesByWorkflow}, the
     * schedule transitions to {@code SUSPENDED_NO_RUN}, the dispatcher
     * excludes it via the {@code state=ACTIVE} predicate, and the row is
     * silent until {@code reactivateWorkflow}/{@code pinWorkflowVersion}
     * re-arms it. Symmetric reactivate path already exists via
     * {@code WorkflowResumeService.reactivateScheduleTriggers}.
     */
    public static boolean suspendsTriggersOnRunTermination(WorkflowType type) {
        return switch (type) {
            case WORKFLOW    -> true;
            case APPLICATION -> true;
        };
    }

    /**
     * Convenience for callers that already have a {@code null}-checked type:
     * a {@code null} type is treated as if the caller had not wired the policy
     * lookup yet - i.e. the lenient pre-policy behavior. For
     * {@link #allowsCancelRunOnlyOrDefault(WorkflowType)} that means "allow"
     * (pre-policy default). For
     * {@link #suspendsTriggersOnRunTerminationOrDefault(WorkflowType)} that means
     * "do NOT suspend" (pre-policy default). The null path is intentionally
     * defensive: orphaned/legacy runs without a workflow association short-circuit
     * out of the speculative cascade.
     *
     * <p>Post-2026-05-13 note: a non-null {@code WORKFLOW} now suspends triggers,
     * but the null path stays false. The asymmetry is deliberate - the typed path
     * is the contract; the null path is fallback for missing data, not a substitute
     * for the typed answer.
     */
    public static boolean allowsCancelRunOnlyOrDefault(WorkflowType type) {
        return type == null || allowsCancelRunOnly(type);
    }

    /** @see #allowsCancelRunOnlyOrDefault(WorkflowType) */
    public static boolean suspendsTriggersOnRunTerminationOrDefault(WorkflowType type) {
        return type != null && suspendsTriggersOnRunTermination(type);
    }
}
