package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the caller-mistake mapping on POST {@code /api/v2/workflows/dag/execute}:
 * an {@link IllegalArgumentException} thrown on the resolver/parser path (e.g. an
 * invalid {@code mockMode} value rejected by {@link EditorRunResolver}) must map
 * to HTTP 400 with the standard failure body, NOT to the generic 500 branch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowExecutionController - IllegalArgumentException maps to 400 on POST /execute")
class WorkflowExecutionControllerBadRequestMappingWebMvcTest {

    private static final String CALLER = "user-42";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowControllerHelper helper;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private EditorRunResolver editorRunResolver;
    @Mock private WorkflowPlanVersionService versionService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowResponseFactory responseFactory;

    private MockMvc mockMvc;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        WorkflowExecutionController controller = new WorkflowExecutionController();
        ReflectionTestUtils.setField(controller, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(controller, "responseFactory", responseFactory);
        ReflectionTestUtils.setField(controller, "helper", helper);
        ReflectionTestUtils.setField(controller, "creditClient", creditClient);
        ReflectionTestUtils.setField(controller, "editorRunResolver", editorRunResolver);
        ReflectionTestUtils.setField(controller, "versionService", versionService);
        ReflectionTestUtils.setField(controller, "orgAccessGuard", orgAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Personal (no-org) workflow owned by the caller: the org gate is skipped
        // and strict-isolation scope passes, so the request reaches the resolver.
        workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(CALLER);
        workflow.setName("Personal workflow");
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));

        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(plan.getId()).thenReturn(workflowId.toString());
        lenient().when(plan.getOriginalPlan()).thenReturn(Map.of());
        when(helper.parseWorkflowPlan(any(), anyString(), anyString())).thenReturn(plan);

        when(creditClient.checkCredits(CALLER)).thenReturn(true);
        // Auto-save short-circuit: canvas equals stored plan, nothing to write.
        lenient().when(versionService.plansAreEqual(any(), any())).thenReturn(true);

        // Delegate to the real factory: the controller declares the concrete
        // WorkflowExecutionResponse return type, so a Map stub would CCE.
        WorkflowResponseFactory realFactory = new WorkflowResponseFactory();
        lenient().when(responseFactory.createFailureResponse(anyString()))
                .thenAnswer(inv -> realFactory.createFailureResponse(inv.getArgument(0, String.class)));
    }

    private String body(String mockMode) {
        return "{\"workflowId\":\"" + workflowId + "\",\"planJson\":\"{}\",\"dataInputs\":{}"
                + (mockMode != null ? ",\"mockMode\":\"" + mockMode + "\"" : "") + "}";
    }

    @Test
    @DisplayName("Regression: an IllegalArgumentException from the run resolver (invalid mockMode) returns 400 with the failure body, not 500")
    void resolverIllegalArgumentMapsTo400() throws Exception {
        when(editorRunResolver.findOrCreateRun(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid mockMode 'bogus': use 'off' or 'all_mcp'"));

        mockMvc.perform(post("/api/v2/workflows/dag/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bogus"))
                        .header("X-User-ID", CALLER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid mockMode 'bogus': use 'off' or 'all_mcp'"));
    }

    @Test
    @DisplayName("Contrast: an unexpected RuntimeException on the same path still returns 500 (the 400 mapping is IAE-specific)")
    void unexpectedRuntimeExceptionStays500() throws Exception {
        when(editorRunResolver.findOrCreateRun(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/api/v2/workflows/dag/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null))
                        .header("X-User-ID", CALLER))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }
}
