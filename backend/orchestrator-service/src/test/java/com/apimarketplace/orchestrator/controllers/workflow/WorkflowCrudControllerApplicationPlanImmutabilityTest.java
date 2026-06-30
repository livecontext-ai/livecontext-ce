package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.orchestrator.common.web.GlobalExceptionHandler;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract of {@code PUT /api/v2/workflows/dag/{id}/plan} when the
 * target workflow is an APPLICATION-type entity (acquired marketplace clone).
 *
 * <p>Run-only applications (decouple-to-editable-workflow): an acquired APPLICATION is
 * a frozen run-only clone - its plan is the contract the acquirer received, not editable
 * in place. Editing now lives in the DECOUPLED editable WORKFLOW twin that acquiring also
 * creates (sourcePublicationId=null, visible in /app/workflows). This human PUT /plan path
 * therefore blanket-rejects any APPLICATION plan edit with HTTP 409
 * ({@code APPLICATION_PLAN_IMMUTABLE}); plain (non-APPLICATION) workflows are untouched and
 * proceed. {@code POST /workflows/{id}/reset-plan} remains the way back to the acquired
 * original.
 *
 * <p>Regression-test bug name: {@code applicationPlanUpdateMustReturn409} - a prior
 * "editable applications" change relaxed this to allow the owner to edit in place; the
 * decouple-to-editable-workflow feature reverts it (editing lives in the WORKFLOW twin).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudController - APPLICATION plan is run-only (PUT /plan -> 409)")
class WorkflowCrudControllerApplicationPlanImmutabilityTest {

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
    private static final UUID APP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // ObjectMapper is @Autowired and not picked up by @InjectMocks-by-type because it's
        // not a @Mock field. Set it manually via reflection so the controller can serialize
        // requests inside tests that need it.
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new OrgAccessDeniedExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("PUT /plan on an APPLICATION returns 409 APPLICATION_PLAN_IMMUTABLE, writes nothing, and never reaches the application write-gate - applicationPlanUpdateMustReturn409")
    void applicationPlanUpdateMustReturn409() throws Exception {
        WorkflowEntity app = new WorkflowEntity();
        ReflectionTestUtils.setField(app, "id", APP_ID);
        app.setTenantId(TENANT);
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        when(workflowRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        String body = new ObjectMapper().writeValueAsString(Map.of("plan", Map.of("triggers", java.util.List.of())));

        mockMvc.perform(put("/api/v2/workflows/dag/{id}/plan", APP_ID)
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_PLAN_IMMUTABLE"));

        // The 409 fires before any plan write or version creation.
        verify(workflowRepository, never()).save(app);
        verify(versionService, never()).createVersion(any(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyString());
        // Run-only: the application write-gate is never consulted on this human edit path.
        verify(workflowManagementService, never()).assertApplicationInstanceWritable(any(), any(), any());
    }

    @Test
    @DisplayName("PUT /plan on a regular WORKFLOW is untouched by the application run-only guard (proceeds, saves) - regularWorkflowPlanUpdateProceeds")
    void regularWorkflowPlanUpdateProceeds() throws Exception {
        WorkflowEntity regular = new WorkflowEntity();
        ReflectionTestUtils.setField(regular, "id", APP_ID);
        regular.setTenantId(TENANT);
        regular.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        when(workflowRepository.findById(APP_ID)).thenReturn(Optional.of(regular));
        when(helper.convertToPlanMap(any()))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("triggers", java.util.List.of())));

        String body = new ObjectMapper().writeValueAsString(Map.of("plan", Map.of("triggers", java.util.List.of())));

        mockMvc.perform(put("/api/v2/workflows/dag/{id}/plan", APP_ID)
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());

        // Non-APPLICATION workflows must not touch the application gate at all.
        verify(workflowManagementService, never()).assertApplicationInstanceWritable(any(), any(), any());
        verify(workflowRepository).save(regular);
    }
}
