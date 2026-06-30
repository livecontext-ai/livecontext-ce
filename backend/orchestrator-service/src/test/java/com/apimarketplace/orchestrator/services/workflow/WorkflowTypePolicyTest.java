package com.apimarketplace.orchestrator.services.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowTypePolicy} - the per-type behavioural rule
 * helper introduced for design v3.5 single-run-on-applications contract.
 *
 * <p>Every {@link WorkflowType} value is covered explicitly; a third type
 * added without updating the policy will surface as a compile error in the
 * exhaustive switch - the test then re-fails to remind the developer to add
 * the new cell here too.
 */
@DisplayName("WorkflowTypePolicy")
class WorkflowTypePolicyTest {

    @Test
    @DisplayName("WORKFLOW allows cancelRunOnly (per-run cancel preserved)")
    void workflowAllowsCancelRunOnly() {
        assertThat(WorkflowTypePolicy.allowsCancelRunOnly(WorkflowType.WORKFLOW)).isTrue();
    }

    @Test
    @DisplayName("APPLICATION refuses cancelRunOnly (single-run contract)")
    void applicationRefusesCancelRunOnly() {
        assertThat(WorkflowTypePolicy.allowsCancelRunOnly(WorkflowType.APPLICATION)).isFalse();
    }

    @Test
    @DisplayName("WORKFLOW suspends triggers on run termination (paused-zombie fix, 2026-05-13)")
    void workflowSuspendsTriggersOnTermination() {
        // Regression guard for the paused-zombie bug. Pre-2026-05-13 this returned false:
        // the user dragged a workflow card to "paused" → cancelWorkflow flipped the run to
        // CANCELLED but left scheduled_executions at state=ACTIVE. The dispatcher then
        // picked the row every minute, the resolver returned NO_PRODUCTION_RUN (only one
        // run at pinned_version, and it was CANCELLED), advanceSchedule was skipped → the
        // row produced a stale next_execution_at + a WARN log forever. The fix routes
        // cancel through triggerClient.suspendSchedulesByWorkflow for WORKFLOW too, same
        // as APPLICATION. Re-arm path is symmetric: reactivateWorkflow → enableSchedulesByWorkflow.
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTermination(WorkflowType.WORKFLOW)).isTrue();
    }

    @Test
    @DisplayName("APPLICATION suspends triggers on run termination (v3.5 §L3 single-run contract)")
    void applicationSuspendsTriggersOnTermination() {
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTermination(WorkflowType.APPLICATION)).isTrue();
    }

    @Test
    @DisplayName("Lenient default: null type allows cancelRunOnly (legacy callers)")
    void nullTypeAllowsCancelRunOnly() {
        assertThat(WorkflowTypePolicy.allowsCancelRunOnlyOrDefault(null)).isTrue();
        assertThat(WorkflowTypePolicy.allowsCancelRunOnlyOrDefault(WorkflowType.WORKFLOW)).isTrue();
        assertThat(WorkflowTypePolicy.allowsCancelRunOnlyOrDefault(WorkflowType.APPLICATION)).isFalse();
    }

    @Test
    @DisplayName("Lenient default: null type does not suspend triggers; WORKFLOW + APPLICATION both do")
    void nullTypeDoesNotSuspendTriggers() {
        // Null/unknown stays lenient (false) - preserves legacy behaviour for any caller
        // that doesn't wire type lookup yet. Both WORKFLOW + APPLICATION suspend (paused-
        // zombie fix 2026-05-13 made WORKFLOW symmetric with APPLICATION).
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTerminationOrDefault(null)).isFalse();
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTerminationOrDefault(WorkflowType.WORKFLOW)).isTrue();
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTerminationOrDefault(WorkflowType.APPLICATION)).isTrue();
    }

    @Test
    @DisplayName("Policy semantics: allowsCancelRunOnly is the API-surface differentiator (WORKFLOW=true, APPLICATION=false); suspendsTriggers is uniformly true")
    void policySemantics() {
        // The two predicates encode INDEPENDENT axes since 2026-05-13:
        //   - allowsCancelRunOnly: does the REST surface accept the run-only cancel
        //     call?  WORKFLOW=true (per-run cancel API exists), APPLICATION=false
        //     (single-run contract refuses it).
        //   - suspendsTriggersOnRunTermination: does cancel/pause have the side-effect
        //     of suspending the workflow's schedules?  Now true for both, since the
        //     paused-zombie fix made WORKFLOW symmetric.
        // The previous "they're opposite" parity invariant was a coincidence of the
        // 2-type taxonomy + the pre-fix policy; not a real contract.
        assertThat(WorkflowTypePolicy.allowsCancelRunOnly(WorkflowType.WORKFLOW)).isTrue();
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTermination(WorkflowType.WORKFLOW)).isTrue();

        assertThat(WorkflowTypePolicy.allowsCancelRunOnly(WorkflowType.APPLICATION)).isFalse();
        assertThat(WorkflowTypePolicy.suspendsTriggersOnRunTermination(WorkflowType.APPLICATION)).isTrue();
    }

    @Test
    @DisplayName("All WorkflowType values have explicit policy mappings (exhaustiveness)")
    void allTypesHaveExplicitMappings() {
        // If a new WorkflowType is added without updating the policy, the switch
        // expressions in WorkflowTypePolicy fail to compile. This test serves as
        // the run-time double-check that no value silently falls through.
        for (WorkflowType type : WorkflowType.values()) {
            // Each call MUST return without throwing - boolean answer required.
            // Since 2026-05-13 the two predicates are independent (see policySemantics);
            // no parity invariant is asserted here, just no-throw + boolean result.
            boolean cancelAllowed = WorkflowTypePolicy.allowsCancelRunOnly(type);
            boolean suspendsTriggers = WorkflowTypePolicy.suspendsTriggersOnRunTermination(type);
            // Touch both values so the compiler doesn't elide the calls.
            assertThat(cancelAllowed || !cancelAllowed).isTrue();
            assertThat(suspendsTriggers || !suspendsTriggers).isTrue();
        }
    }
}
