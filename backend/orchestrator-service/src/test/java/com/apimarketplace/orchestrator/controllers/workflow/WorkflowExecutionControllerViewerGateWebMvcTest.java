package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.auth.client.access.OrgAccessGuardImpl;
import com.apimarketplace.common.credit.CreditConsumptionClient;
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
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AuthClient authClient;

    private MockMvc mockMvc;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        WorkflowExecutionController controller = new WorkflowExecutionController();
        ReflectionTestUtils.setField(controller, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(controller, "responseFactory", responseFactory);
        ReflectionTestUtils.setField(controller, "helper", helper);
        ReflectionTestUtils.setField(controller, "creditClient", creditClient);
        // REAL guard: the deny must come from the central isRoleWriteBlocked
        // logic, not from a stubbed canWrite.
        ReflectionTestUtils.setField(controller, "orgAccessGuard", new OrgAccessGuardImpl(authClient));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OrgAccessDeniedExceptionHandler())
                .build();

        workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(CALLER);
        workflow.setOrganizationId(ORG);
        workflow.setName("Org workflow");
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));

        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(plan.getId()).thenReturn(workflowId.toString());
        when(helper.parseWorkflowPlan(any(), anyString(), anyString())).thenReturn(plan);

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
        org.mockito.Mockito.verifyNoInteractions(authClient, creditClient);
    }

    @Test
    @DisplayName("Positive control: MEMBER passes the org gate (reaches the credit check)")
    void memberPassesTheOrgGate() throws Exception {
        when(authClient.getWriteRestrictedResourceIds(ORG, CALLER, "workflow")).thenReturn(Set.of());
        // Deny credits so the request stops right AFTER the org gate: a 402
        // (not 403) proves the gate let the MEMBER through.
        when(creditClient.checkCredits(CALLER)).thenReturn(false);

        mockMvc.perform(post("/api/v2/workflows/dag/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header("X-User-ID", CALLER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isPaymentRequired());
    }
}
