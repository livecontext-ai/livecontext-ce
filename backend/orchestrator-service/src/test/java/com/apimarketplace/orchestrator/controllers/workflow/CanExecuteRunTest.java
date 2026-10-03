package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the shared run-execution authorization predicate (LC-012, security audit
 * 2026-08-13).
 *
 * <p>"Execute something on this run" is reachable through several controllers: firing a
 * trigger, firing an interface action, step-by-step execute, schedule execute-now, rerun.
 * Only the workflow object and the storage explorer had the intra-org layer, so a VIEWER, or a
 * MEMBER explicitly denied the Gmail workflow, could advance a run and cause a fresh fetch of
 * the owner's mailbox. Putting the decision in ONE predicate is what stops the next entry point
 * from quietly shipping without it; these tests pin the predicate's contract.
 */
@DisplayName("WorkflowControllerHelper.canExecuteRun")
class CanExecuteRunTest {

    private static final String CALLER = "user-1";
    private static final String ORG = "org-1";

    private final OrgAccessGuard guard = mock(OrgAccessGuard.class);
    private final UUID workflowId = UUID.randomUUID();

    private WorkflowRunEntity run(String orgId, boolean withWorkflow) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "organizationId", orgId);
        if (withWorkflow) {
            WorkflowEntity workflow = new WorkflowEntity();
            ReflectionTestUtils.setField(workflow, "id", workflowId);
            ReflectionTestUtils.setField(run, "workflow", workflow);
        }
        return run;
    }

    @Nested
    @DisplayName("inside an organization")
    class InsideAnOrg {

        @Test
        @DisplayName("delegates to the deny-list, keyed on the run's PARENT WORKFLOW")
        void keyedOnTheWorkflow() {
            // Denying a member the Gmail workflow must deny them every run of it, so the id
            // handed to the guard has to be the workflow's, never the run's.
            when(guard.canWrite(ORG, CALLER, "workflow", workflowId.toString(), "MEMBER")).thenReturn(true);

            assertThat(WorkflowControllerHelper.canExecuteRun(run(ORG, true), CALLER, "MEMBER", guard)).isTrue();

            verify(guard).canWrite(ORG, CALLER, "workflow", workflowId.toString(), "MEMBER");
        }

        @Test
        @DisplayName("refuses when the guard refuses")
        void refusesWhenDenied() {
            when(guard.canWrite(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            assertThat(WorkflowControllerHelper.canExecuteRun(run(ORG, true), CALLER, "VIEWER", guard)).isFalse();
        }

        @Test
        @DisplayName("passes a null role through, rather than inventing one")
        void passesNullRoleThrough() {
            // The gateway injects no role for a share-token visitor. Substituting a default here
            // would silently grant or deny; the guard is the only thing entitled to decide.
            when(guard.canWrite(ORG, CALLER, "workflow", workflowId.toString(), null)).thenReturn(true);

            assertThat(WorkflowControllerHelper.canExecuteRun(run(ORG, true), CALLER, null, guard)).isTrue();

            verify(guard).canWrite(ORG, CALLER, "workflow", workflowId.toString(), null);
        }
    }

    @Nested
    @DisplayName("fail-closed cases")
    class FailClosed {

        @Test
        @DisplayName("a missing guard REFUSES rather than skipping the check")
        void missingGuardRefuses() {
            // The regression this prevents: an @Autowired(required = false) guard turned every
            // call site into a no-op if the bean were absent, while the code still read as if
            // the check ran.
            assertThat(WorkflowControllerHelper.canExecuteRun(run(ORG, true), CALLER, "VIEWER", null)).isFalse();
        }

        @Test
        @DisplayName("a run with no resolvable workflow REFUSES")
        void unresolvableWorkflowRefuses() {
            // Nothing to key the deny-list on means the decision cannot be made, and an
            // undecidable authorization question is a refusal.
            assertThat(WorkflowControllerHelper.canExecuteRun(run(ORG, false), CALLER, "MEMBER", guard)).isFalse();
            verify(guard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a null run REFUSES")
        void nullRunRefuses() {
            assertThat(WorkflowControllerHelper.canExecuteRun(null, CALLER, "OWNER", guard)).isFalse();
        }
    }

    @Nested
    @DisplayName("outside an organization")
    class Personal {

        @Test
        @DisplayName("a personal run is allowed without consulting the guard")
        void personalRunSkipsTheGuard() {
            // There is no role to enforce outside a workspace, and calling the guard with a null
            // org would ask it a question it cannot answer.
            assertThat(WorkflowControllerHelper.canExecuteRun(run(null, true), CALLER, null, guard)).isTrue();
            assertThat(WorkflowControllerHelper.canExecuteRun(run("  ", true), CALLER, null, guard)).isTrue();

            verify(guard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        }
    }
}
