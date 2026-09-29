package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService;
import com.apimarketplace.orchestrator.services.resume.StepRerunService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the run-control write gap: pause / stop / resume / rerun and
 * {@code PUT /runs/{id}/plan} checked org SCOPE but never the caller's ROLE, so a
 * read-only VIEWER could drive, stop or rewrite a live run whose nodes later fire with
 * the owner's credentials. {@code PUT /plan} also skipped the per-member deny-list
 * ({@link OrgAccessGuard#canWrite}) that execute applies, so a member restricted from a
 * workflow could still rewrite the plan of one of its runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - run control is a write (VIEWER + deny-list)")
class WorkflowRunControllerRunControlWriteGateTest {

    private static final String RUN_ID = "run_<id>";
    private static final String TENANT = "user-7";
    private static final String ORG = "org-7";
    private static final UUID WORKFLOW_ID = UUID.fromString("5b0f5c1e-4d3a-4c55-9d7e-3f6f0a2b9c11");

    @Mock private WorkflowResumeService resumeService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private StepRerunService stepRerunService;
    @Mock private AutoRestartExecutionService autoRestartExecutionService;
    @Mock private OrgAccessGuard orgAccessGuard;

    @InjectMocks private WorkflowRunController controller;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private WorkflowRunEntity orgRun() {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
        lenient().when(run.getWorkflow()).thenReturn(workflow);
        lenient().when(run.getTenantId()).thenReturn("owner-1");
        lenient().when(run.getOrganizationId()).thenReturn(ORG);
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID);
        lenient().when(run.getStatus()).thenReturn(RunStatus.RUNNING);
        return run;
    }

    /** PUT /plan reads the org from the bound request, as the gateway sets it. */
    private void bindOrgRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", ORG);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static Map<String, Object> planBody() {
        return Map.of("plan", Map.of("triggers", List.of(), "edges", List.of()));
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Nested
    @DisplayName("VIEWER is refused before anything is read or written")
    class Viewer {

        @Test
        @DisplayName("pause by a VIEWER is 403 and never reaches the resume service")
        void pauseRefused() {
            assertForbidden(controller.pauseWorkflow(RUN_ID, TENANT, ORG, "VIEWER"));
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("stop by a VIEWER (any case) is 403 and never reaches the resume service")
        void stopRefused() {
            assertForbidden(controller.stopWorkflow(RUN_ID, TENANT, ORG, " viewer "));
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("resume by a VIEWER is 403 and never reaches the resume service")
        void resumeRefused() {
            assertForbidden(controller.resumeWorkflow(RUN_ID, TENANT, ORG, "VIEWER"));
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("rerun with a plan by a VIEWER is 403: run.plan is NOT rewritten and nothing reruns")
        void rerunRefusedWithoutPlanWrite() {
            assertForbidden(controller.rerunFromStep(RUN_ID, "mcp:step", null, planBody(), TENANT, ORG, "VIEWER"));
            verify(resumeService, never()).updateRunPlan(anyString(), any());
            verifyNoInteractions(stepRerunService, autoRestartExecutionService);
        }

        @Test
        @DisplayName("PUT /runs/{id}/plan by a VIEWER is 403 and the plan is never written")
        void planUpdateRefused() {
            bindOrgRequest();
            assertForbidden(controller.updateRunPlan(RUN_ID, TENANT, "VIEWER", planBody()));
            verify(resumeService, never()).updateRunPlan(anyString(), any());
        }

        @Test
        @DisplayName("the VIEWER role outside a workspace (no org) is not a workspace role: pause proceeds")
        void viewerWithoutOrgIsNotBlocked() {
            WorkflowRunEntity personal = mock(WorkflowRunEntity.class);
            when(personal.getTenantId()).thenReturn(TENANT);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(personal));
            WorkflowRunState state = mock(WorkflowRunState.class);
            when(state.status()).thenReturn(RunStatus.PAUSED);
            when(state.readySteps()).thenReturn(java.util.Set.of());
            when(resumeService.pauseWorkflow(RUN_ID)).thenReturn(state);

            ResponseEntity<?> response = controller.pauseWorkflow(RUN_ID, TENANT, null, "VIEWER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(resumeService).pauseWorkflow(RUN_ID);
        }
    }

    @Nested
    @DisplayName("per-member deny-list (OrgAccessGuard.canWrite) applies like it does on execute")
    class DenyList {

        @Test
        @DisplayName("PUT /plan by a member restricted from the workflow is 403 and the plan is never written")
        void restrictedMemberCannotRewritePlan() {
            bindOrgRequest();
            WorkflowRunEntity run = orgRun();
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(orgAccessGuard.canWrite(ORG, TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);

            assertForbidden(controller.updateRunPlan(RUN_ID, TENANT, "MEMBER", planBody()));
            verify(resumeService, never()).updateRunPlan(anyString(), any());
        }

        @Test
        @DisplayName("rerun by a restricted member is 403 before the plan write and the rerun")
        void restrictedMemberCannotRerun() {
            WorkflowRunEntity run = orgRun();
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(orgAccessGuard.canWrite(ORG, TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);

            assertForbidden(controller.rerunFromStep(RUN_ID, "mcp:step", null, planBody(), TENANT, ORG, "MEMBER"));
            verify(resumeService, never()).updateRunPlan(anyString(), any());
            verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
        }

        @Test
        @DisplayName("stop by a restricted member is 403")
        void restrictedMemberCannotStop() {
            WorkflowRunEntity run = orgRun();
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(orgAccessGuard.canWrite(ORG, TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);

            assertForbidden(controller.stopWorkflow(RUN_ID, TENANT, ORG, "MEMBER"));
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("an unrestricted MEMBER may still rewrite the plan: the gate is a no-op for writers")
        void unrestrictedMemberUpdatesPlan() {
            bindOrgRequest();
            WorkflowRunEntity run = orgRun();
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(orgAccessGuard.canWrite(ORG, TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(true);
            when(resumeService.updateRunPlan(anyString(), any())).thenReturn(mock(WorkflowPlan.class));

            ResponseEntity<?> response = controller.updateRunPlan(RUN_ID, TENANT, "MEMBER", planBody());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(resumeService).updateRunPlan(anyString(), any());
        }

        @Test
        @DisplayName("an unrestricted MEMBER may still pause: the role is forwarded to the guard as received")
        void unrestrictedMemberPauses() {
            WorkflowRunEntity run = orgRun();
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(orgAccessGuard.canWrite(ORG, TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(true);
            WorkflowRunState state = mock(WorkflowRunState.class);
            when(state.status()).thenReturn(RunStatus.PAUSED);
            when(state.readySteps()).thenReturn(java.util.Set.of());
            when(resumeService.pauseWorkflow(RUN_ID)).thenReturn(state);

            ResponseEntity<?> response = controller.pauseWorkflow(RUN_ID, TENANT, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(resumeService).pauseWorkflow(RUN_ID);
        }
    }
}
