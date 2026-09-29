package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.auth.client.access.OrgAccessGuardImpl;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the (previously unstated) consequence of the central VIEWER read-only
 * gate on POST {@code /api/v2/workflows/dag/execute}: a VIEWER cannot execute
 * an org workflow. This is deliberate and fail-closed - the endpoint
 * auto-saves the submitted plan before running (see the auto-save block in
 * {@code executeWorkflow}), so execution IS a write. Uses the REAL
 * {@link OrgAccessGuardImpl} so the test exercises the central role gate, not
 * a mocked decision.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowExecutionController - VIEWER role gate on POST /execute")
class WorkflowExecutionControllerViewerGateWebMvcTest {

    private static final String CALLER = "user-42";
    private static final String ORG = "org-aaa";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowResponseFactory responseFactory;
    @Mock private WorkflowControllerHelper helper;
    @Mock private AuthClient authClient;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;
    @Mock private com.apimarketplace.orchestrator.services.resume.WorkflowResumeService resumeService;

    private MockMvc mockMvc;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        WorkflowExecutionController controller = new WorkflowExecutionController();
        ReflectionTestUtils.setField(controller, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(controller, "responseFactory", responseFactory);
        ReflectionTestUtils.setField(controller, "helper", helper);
        // REAL guard: the deny must come from the central isRoleWriteBlocked
        // logic, not from a stubbed canWrite.
        ReflectionTestUtils.setField(controller, "orgAccessGuard", new OrgAccessGuardImpl(authClient));
        ReflectionTestUtils.setField(controller, "runRepository", runRepository);
        ReflectionTestUtils.setField(controller, "resumeService", resumeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OrgAccessDeniedExceptionHandler())
                .build();

        workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(CALLER);
        workflow.setOrganizationId(ORG);
        workflow.setName("Org workflow");
        lenient().when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));

        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(plan.getId()).thenReturn(workflowId.toString());
        lenient().when(helper.parseWorkflowPlan(any(), anyString(), anyString())).thenReturn(plan);

        // Delegate to the real factory: the controller declares the concrete
        // WorkflowExecutionResponse return type, so a Map stub would CCE.
        com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory realFactory =
                new com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory();
        lenient().when(responseFactory.createFailureResponse(anyString()))
                .thenAnswer(inv -> realFactory.createFailureResponse(inv.getArgument(0, String.class)));
    }

    private String body() {
        return "{\"workflowId\":\"" + workflowId + "\",\"planJson\":\"{}\",\"dataInputs\":{}}";
    }

    @Test
    @DisplayName("Regression: VIEWER in the workflow's org gets 403 before any execution or credit check")
    void viewerCannotExecuteOrgWorkflow() throws Exception {
        mockMvc.perform(post("/api/v2/workflows/dag/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header("X-User-ID", CALLER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        // The role gate fires without consulting the per-resource deny-list and
        // before the credit check: no AuthClient or credit interaction happened.
        org.mockito.Mockito.verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("Regression: VIEWER cannot START a pending run (POST /{workflowId}/runs/{runId}/start)")
    void viewerCannotStartPendingRun() throws Exception {
        com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
                new com.apimarketplace.orchestrator.domain.WorkflowRunEntity();
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        run.setWorkflow(wf);
        run.setRunIdPublic("run-p1");
        run.setTenantId("owner-9");
        run.setOrganizationId(ORG);
        when(runRepository.findByRunIdPublic("run-p1")).thenReturn(Optional.of(run));

        mockMvc.perform(post("/api/v2/workflows/dag/" + workflowId + "/runs/run-p1/start")
                        .header("X-User-ID", CALLER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(resumeService);
    }

    @Test
    @DisplayName("Positive control: MEMBER passes the org gate (403 is VIEWER-specific)")
    void memberPassesTheOrgGate() throws Exception {
        when(authClient.getWriteRestrictedResourceIds(ORG, CALLER, "workflow")).thenReturn(Set.of());

        // This used to assert 402: the credit gate stopped the request one line
        // after the org gate, which made "not 403" observable. That gate is gone
        // (an out-of-credit run now fails on its trigger node so the user can see
        // it), so the proof is the gate's own verdict instead: the per-resource
        // deny-list WAS consulted for a MEMBER, and no 403 was raised.
        mockMvc.perform(post("/api/v2/workflows/dag/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header("X-User-ID", CALLER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        verify(authClient).getWriteRestrictedResourceIds(ORG, CALLER, "workflow");
    }
}
