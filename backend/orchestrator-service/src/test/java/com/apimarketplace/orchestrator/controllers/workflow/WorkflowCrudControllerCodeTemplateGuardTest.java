package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.common.web.GlobalExceptionHandler;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LC-018 on the two REST plan-save routes the visual builder uses:
 * {@code POST /api/v2/workflows/dag} (create) and {@code PUT /api/v2/workflows/dag/{id}/plan}
 * (update). The CASA remediation first made both refuse a {@code core:code} body with a
 * {@code {{...}}} expression OUTSIDE any string literal.
 *
 * <p>Regression review 2026-09-29: refusing that shape at save time blocked EVERY existing workflow
 * holding one such node (even a commented one) from being saved again, whichever node the user
 * edited, while protecting nothing: the run splices every resolved value as a complete literal (data,
 * never source), see CodeNode.spliceAtCodePosition. The refusal was removed; these tests pin that both
 * routes save the code-position shape and the in-literal shape alike. The MCP builder still WARNS.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudController - a code body with {{...}} never blocks a save (LC-018 is enforced at run time)")
class WorkflowCrudControllerCodeTemplateGuardTest {

    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock private WorkflowManagementService workflowManagementService;
    @Mock private com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory responseFactory;
    @Mock private WorkflowControllerHelper helper;
    @Mock private com.apimarketplace.trigger.client.TriggerClient triggerClient;
    @Mock private com.apimarketplace.orchestrator.trigger.TriggerTypeDetector triggerTypeDetector;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService versionService;
    @Mock private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;
    @Mock private com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService pinAwareTriggerSyncService;
    @Mock private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    @InjectMocks
    private WorkflowCrudController controller;

    private MockMvc mockMvc;

    private static final String TENANT = "tenant-1";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    /** A code node body with the placeholder at a CODE position: outside any string literal. */
    private static final Map<String, Object> UNSAFE_CODE_CORE = Map.of(
            "id", "core:process",
            "label", "Process",
            "type", "code",
            "code", Map.of("language", "javascript", "code",
                    "const cmd = {{mcp:fetch_mail.output.subject}};"));

    /** The same shape, placeholder INSIDE a string literal: resolves fine at run time. */
    private static final Map<String, Object> SAFE_CODE_CORE = Map.of(
            "id", "core:process",
            "label", "Process",
            "type", "code",
            "code", Map.of("language", "javascript", "code",
                    "const cmd = '{{mcp:fetch_mail.output.subject}}';"));

    @BeforeEach
    void setUp() {
        // ObjectMapper is @Autowired, not a @Mock field, so @InjectMocks leaves it null - set it
        // manually, exactly as the sibling APPLICATION-immutability test class does.
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(),
                        new com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("PUT /{id}/plan (frontend builder edit path)")
    class UpdatePlan {

        @BeforeEach
        void stubWorkflow() {
            WorkflowEntity workflow = new WorkflowEntity();
            ReflectionTestUtils.setField(workflow, "id", WORKFLOW_ID);
            workflow.setTenantId(TENANT);
            workflow.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        }

        @Test
        @DisplayName("Regression 2026-09-29: an existing code-position template still saves - codePositionTemplateStillSavesOnUpdate")
        void codePositionTemplateStillSavesOnUpdate() throws Exception {
            Map<String, Object> plan = new java.util.LinkedHashMap<>(Map.of("cores", List.of(UNSAFE_CODE_CORE)));
            when(helper.convertToPlanMap(any())).thenReturn(plan);

            String body = new ObjectMapper().writeValueAsString(Map.of("plan", plan));

            mockMvc.perform(put("/api/v2/workflows/dag/{id}/plan", WORKFLOW_ID)
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is2xxSuccessful());

            verify(workflowRepository).save(any());
        }

        @Test
        @DisplayName("an in-literal placeholder still saves - inLiteralTemplateStillSaves")
        void inLiteralTemplateStillSaves() throws Exception {
            Map<String, Object> plan = new java.util.LinkedHashMap<>(
                    Map.of("cores", List.of(SAFE_CODE_CORE)));
            when(helper.convertToPlanMap(any())).thenReturn(plan);

            String body = new ObjectMapper().writeValueAsString(Map.of("plan", plan));

            mockMvc.perform(put("/api/v2/workflows/dag/{id}/plan", WORKFLOW_ID)
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is2xxSuccessful());

            verify(workflowRepository).save(any());
        }
    }

    @Nested
    @DisplayName("POST / (frontend builder create/save path)")
    class SaveWorkflow {

        @BeforeEach
        void stubParse() {
            WorkflowPlan mockPlan = mock(WorkflowPlan.class);
            when(workflowManagementService.parsePlanWithTenantId(anyString(), anyString()))
                    .thenReturn(mockPlan);
        }

        @Test
        @DisplayName("Regression 2026-09-29: a code-position template still reaches saveWorkflow - codePositionTemplateStillSavesOnCreate")
        void codePositionTemplateStillSavesOnCreate() throws Exception {
            WorkflowManagementService.SaveResult saveResult = mock(WorkflowManagementService.SaveResult.class);
            when(workflowManagementService.saveWorkflow(any(), any(), any(), any(), any()))
                    .thenReturn(saveResult);
            when(workflowManagementService.buildSaveResponse(any())).thenReturn(Map.of("success", true));

            String planJson = new ObjectMapper().writeValueAsString(
                    Map.of("cores", List.of(UNSAFE_CODE_CORE)));
            String body = new ObjectMapper().writeValueAsString(Map.of("planJson", planJson));

            mockMvc.perform(post("/api/v2/workflows/dag")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is2xxSuccessful());

            verify(workflowManagementService).saveWorkflow(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an in-literal placeholder still reaches saveWorkflow - inLiteralTemplateStillReachesSave")
        void inLiteralTemplateStillReachesSave() throws Exception {
            WorkflowManagementService.SaveResult saveResult = mock(WorkflowManagementService.SaveResult.class);
            when(workflowManagementService.saveWorkflow(any(), any(), any(), any(), any()))
                    .thenReturn(saveResult);
            when(workflowManagementService.buildSaveResponse(any())).thenReturn(Map.of("success", true));

            String planJson = new ObjectMapper().writeValueAsString(
                    Map.of("cores", List.of(SAFE_CODE_CORE)));
            String body = new ObjectMapper().writeValueAsString(Map.of("planJson", planJson));

            mockMvc.perform(post("/api/v2/workflows/dag")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is2xxSuccessful());

            verify(workflowManagementService).saveWorkflow(any(), any(), any(), any(), any());
        }
    }
}
