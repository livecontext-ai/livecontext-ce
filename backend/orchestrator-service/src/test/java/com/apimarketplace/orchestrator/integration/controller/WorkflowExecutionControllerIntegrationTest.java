package com.apimarketplace.orchestrator.integration.controller;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowPlanRequest;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotReader;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WorkflowExecutionController.
 * Tests execution endpoints at /api/v2/workflows/dag.
 *
 * <p>Validates HTTP contract for workflow execution, validation,
 * start-run, and streaming endpoints.</p>
 */
@DisplayName("WorkflowExecutionController Integration Tests")
class WorkflowExecutionControllerIntegrationTest extends BaseControllerIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @MockitoBean
    private WorkflowExecutionService executionService;

    @MockitoBean
    private WorkflowStreamingService streamingService;

    @MockitoBean
    private SnapshotReader sseSnapshotReader;

    @MockitoBean
    private WorkflowResumeService resumeService;

    @MockitoBean
    private ExecutionContextManager contextManager;

    @MockitoBean
    private TriggerTypeDetector triggerTypeDetector;

    @MockitoBean
    private TriggerClient triggerClient;

    @MockitoBean
    private CreditConsumptionClient creditClient;

    @MockitoBean
    private OrgAccessGuard orgAccessGuard;

    private WorkflowEntity testWorkflow;

    @BeforeEach
    void setUp() {
        when(triggerClient.getTokensForWorkflow(any(UUID.class)))
            .thenReturn(Map.of());
        when(creditClient.checkCredits(any())).thenReturn(true);
        lenient().when(orgAccessGuard.canWrite(any(), any(), any(), any(), any()))
            .thenReturn(true);

        testWorkflow = createAndSaveWorkflow(TENANT_ID, "Execution Test Workflow");
    }

    // =========================================================================
    // POST /execute - Execute Workflow
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/execute")
    class ExecuteWorkflow {

        @Test
        @DisplayName("Returns 400 when planJson is blank")
        void returns400WhenPlanJsonBlank() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("planJson", "");
            request.put("dataInputs", Map.of());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 400 when planJson is missing")
        void returns400WhenPlanJsonMissing() throws Exception {
            Map<String, Object> request = Map.of("dataInputs", Map.of());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Accepts valid execution request with minimal plan")
        void acceptsValidExecutionRequest() throws Exception {
            // Mock execution service to return a valid execution
            WorkflowExecution mockExecution = mock(WorkflowExecution.class);
            WorkflowPlan mockPlan = mock(WorkflowPlan.class);
            when(mockExecution.getRunId()).thenReturn("run-123");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getStartTime()).thenReturn(Instant.now());
            when(mockPlan.getId()).thenReturn(testWorkflow.getId().toString());
            when(mockPlan.getTenantId()).thenReturn(TENANT_ID);
            when(mockPlan.getMcps()).thenReturn(List.of());

            // EditorRunResolver looks up the run by runIdPublic after executionService.createExecution().
            // Since executionService is mocked, pre-persist a stub run so findByRunIdPublic succeeds.
            createAndSaveRun(testWorkflow, TENANT_ID, "run-123");

            when(executionService.createExecution(any(), anyMap(), any())).thenReturn(mockExecution);
            when(triggerTypeDetector.hasReusableTrigger(any(WorkflowPlan.class))).thenReturn(false);

            Map<String, Object> planData = createMinimalPlanData();
            String planJson = objectMapper.writeValueAsString(planData);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", planJson);
            request.put("dataInputs", Map.of());
            request.put("workflowId", testWorkflow.getId().toString());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Shared application bootstrap can create the scoped application run")
        void sharedApplicationBootstrapCanCreateScopedApplicationRun() throws Exception {
            UUID publicationId = UUID.randomUUID();
            testWorkflow.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
            testWorkflow.setSourcePublicationId(publicationId);
            workflowRepository.save(testWorkflow);

            WorkflowExecution mockExecution = mock(WorkflowExecution.class);
            WorkflowPlan mockPlan = mock(WorkflowPlan.class);
            when(mockExecution.getRunId()).thenReturn("run-shared-app-123");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getStartTime()).thenReturn(Instant.now());
            when(mockPlan.getId()).thenReturn(testWorkflow.getId().toString());
            when(mockPlan.getTenantId()).thenReturn(TENANT_ID);
            when(mockPlan.getMcps()).thenReturn(List.of());

            createAndSaveRun(testWorkflow, TENANT_ID, "run-shared-app-123");

            when(executionService.createExecution(any(), anyMap(), any())).thenReturn(mockExecution);
            when(triggerTypeDetector.hasReusableTrigger(any(WorkflowPlan.class))).thenReturn(false);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", objectMapper.writeValueAsString(createMinimalPlanData()));
            request.put("dataInputs", Map.of());
            request.put("workflowId", testWorkflow.getId().toString());
            request.put("source", "application");
            request.put("publicationId", publicationId.toString());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID)
                    .header("X-Share-Context", "true")
                    .header("X-Share-Resource-Type", "APPLICATION")
                    .header("X-Share-Resource-Token", publicationId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-shared-app-123"))
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Shared application bootstrap runs the stored application plan instead of the request plan")
        void sharedApplicationBootstrapRunsStoredPlanWhenRequestPlanDiffers() throws Exception {
            UUID publicationId = UUID.randomUUID();
            Map<String, Object> storedPlan = new HashMap<>(createMinimalPlanData());
            storedPlan.put("name", "Stored Application Plan");
            Map<String, Object> forgedRequestPlan = new HashMap<>(createMinimalPlanData());
            forgedRequestPlan.put("name", "Forged Request Plan");

            testWorkflow.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
            testWorkflow.setSourcePublicationId(publicationId);
            testWorkflow.setPlan(storedPlan);
            workflowRepository.save(testWorkflow);

            WorkflowExecution mockExecution = mock(WorkflowExecution.class);
            WorkflowPlan mockPlan = mock(WorkflowPlan.class);
            when(mockExecution.getRunId()).thenReturn("run-shared-app-stored-plan");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getStartTime()).thenReturn(Instant.now());
            when(mockPlan.getId()).thenReturn(testWorkflow.getId().toString());
            when(mockPlan.getTenantId()).thenReturn(TENANT_ID);
            when(mockPlan.getMcps()).thenReturn(List.of());

            createAndSaveRun(testWorkflow, TENANT_ID, "run-shared-app-stored-plan");

            when(executionService.createExecution(any(), anyMap(), any())).thenReturn(mockExecution);
            when(triggerTypeDetector.hasReusableTrigger(any(WorkflowPlan.class))).thenReturn(false);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", objectMapper.writeValueAsString(forgedRequestPlan));
            request.put("dataInputs", Map.of());
            request.put("workflowId", testWorkflow.getId().toString());
            request.put("source", "application");
            request.put("publicationId", publicationId.toString());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID)
                    .header("X-Share-Context", "true")
                    .header("X-Share-Resource-Type", "APPLICATION")
                    .header("X-Share-Resource-Token", publicationId.toString())
                    .header("X-Share-Resource-Id", testWorkflow.getId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-shared-app-stored-plan"))
                .andExpect(jsonPath("$.success").value(true));

            verify(executionService).createExecution(
                    argThat(plan -> "Stored Application Plan".equals(plan.getOriginalPlan().get("name"))),
                    anyMap(),
                    any());
        }

        @Test
        @DisplayName("Shared application bootstrap rejects a mismatched publication")
        void sharedApplicationBootstrapRejectsMismatchedPublication() throws Exception {
            UUID publicationId = UUID.randomUUID();
            testWorkflow.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
            testWorkflow.setSourcePublicationId(publicationId);
            workflowRepository.save(testWorkflow);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", objectMapper.writeValueAsString(createMinimalPlanData()));
            request.put("dataInputs", Map.of());
            request.put("workflowId", testWorkflow.getId().toString());
            request.put("source", "application");
            request.put("publicationId", UUID.randomUUID().toString());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Share-Context", "true")
                    .header("X-Share-Resource-Type", "APPLICATION")
                    .header("X-Share-Resource-Token", publicationId.toString())
                    .header("X-Share-Resource-Id", testWorkflow.getId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());

            verify(executionService, never()).createExecution(any(), anyMap(), any());
        }

        @Test
        @DisplayName("Datasource trigger returns WAITING_TRIGGER instead of auto-firing")
        void datasourceTriggerWaitsForExplicitFire() throws Exception {
            // Regression: previously WorkflowExecutionController had a special-case
            // branch that auto-fired datasource triggers in AUTO mode. They now wait
            // for explicit fire (UI play button or agent execute) like every other
            // reusable trigger. Production firing is independent - handled by the
            // trigger-service subscription registry on real row events.
            WorkflowExecution mockExecution = mock(WorkflowExecution.class);
            WorkflowPlan mockPlan = mock(WorkflowPlan.class);
            when(mockExecution.getRunId()).thenReturn("run-ds-456");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(mockExecution.getStartTime()).thenReturn(Instant.now());
            when(mockPlan.getId()).thenReturn(testWorkflow.getId().toString());
            when(mockPlan.getTenantId()).thenReturn(TENANT_ID);
            when(mockPlan.getMcps()).thenReturn(List.of());
            when(mockPlan.getTriggers()).thenReturn(List.of());

            createAndSaveRun(testWorkflow, TENANT_ID, "run-ds-456");

            when(executionService.createExecution(any(), anyMap(), any())).thenReturn(mockExecution);
            // hasReusableTrigger is the only check the controller still consults for
            // datasource triggers - the prior `hasDatasourceTrigger` special case is gone.
            when(triggerTypeDetector.hasReusableTrigger(any(WorkflowPlan.class))).thenReturn(true);
            when(triggerTypeDetector.hasWebhookTrigger(any(WorkflowPlan.class))).thenReturn(false);

            Map<String, Object> planData = createMinimalPlanData();
            String planJson = objectMapper.writeValueAsString(planData);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", planJson);
            request.put("dataInputs", Map.of());
            request.put("workflowId", testWorkflow.getId().toString());

            mockMvc.perform(post("/api/v2/workflows/dag/execute")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-ds-456"))
                .andExpect(jsonPath("$.status").value(RunStatus.WAITING_TRIGGER.getValue()))
                .andExpect(jsonPath("$.executionMode").value("automatic"));

            // Regression guard: the controller must NOT call execute() on the
            // WorkflowExecutionService for a datasource-trigger run. The previous
            // auto-fire branch invoked startAsyncExecution → executionService.execute
            // via CompletableFuture.runAsync, so a plain never() would race the worker.
            // after(300).never() waits 300ms first, giving any rogue background dispatch
            // ample time to land before asserting non-invocation.
            verify(executionService, after(300).never()).execute(any());
        }
    }

    // =========================================================================
    // POST /validate - Validate Workflow
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/validate")
    class ValidateWorkflow {

        @Test
        @DisplayName("Returns 400 when planJson is blank")
        void returns400WhenPlanJsonBlank() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("planJson", "");
            request.put("dataInputs", Map.of());

            mockMvc.perform(post("/api/v2/workflows/dag/validate")
                    .header(X_USER_ID, TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Accepts valid validation request")
        void acceptsValidRequest() throws Exception {
            Map<String, Object> planData = createMinimalPlanData();
            String planJson = objectMapper.writeValueAsString(planData);

            Map<String, Object> request = new HashMap<>();
            request.put("planJson", planJson);
            request.put("dataInputs", Map.of());

            mockMvc.perform(post("/api/v2/workflows/dag/validate")
                    .header(X_USER_ID, TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        }
    }

    // =========================================================================
    // POST /{workflowId}/runs/{runId}/start - Start Workflow Run
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/{workflowId}/runs/{runId}/start")
    class StartWorkflowRun {

        @Test
        @DisplayName("Returns 400 when X-User-ID header is missing")
        void returns400WhenUserIdMissing() throws Exception {
            mockMvc.perform(post("/api/v2/workflows/dag/{workflowId}/runs/{runId}/start",
                    testWorkflow.getId(), "run-123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("X-User-ID header is required"));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            mockMvc.perform(post("/api/v2/workflows/dag/{workflowId}/runs/{runId}/start",
                    testWorkflow.getId(), "nonexistent-run")
                    .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 404 when run is outside caller scope")
        void returns404WhenDifferentTenant() throws Exception {
            // Create a run entity for a different tenant
            WorkflowRunEntity run = createAndSaveRun(testWorkflow, OTHER_TENANT_ID, "run-forbidden");

            mockMvc.perform(post("/api/v2/workflows/dag/{workflowId}/runs/{runId}/start",
                    testWorkflow.getId(), "run-forbidden")
                    .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // Workflow Run Query Tests - GET /api/workflows/{id}/runs
    // =========================================================================

    @Nested
    @DisplayName("GET /api/workflows/{workflowId}/runs")
    class ListRuns {

        @Test
        @DisplayName("Returns empty list when no runs exist")
        void returnsEmptyListWhenNoRuns() throws Exception {
            mockMvc.perform(get("/api/workflows/{workflowId}/runs", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("Returns runs for workflow with pagination params")
        void returnsRunsWithPagination() throws Exception {
            mockMvc.perform(get("/api/workflows/{workflowId}/runs", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID)
                    .param("limit", "5")
                    .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        }
    }

    @Nested
    @DisplayName("GET /api/workflows/runs/{runIdPublic}")
    class GetRunByPublicId {

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenNotFound() throws Exception {
            mockMvc.perform(get("/api/workflows/runs/{runIdPublic}", "nonexistent-run-id")
                    .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns run when found")
        void returnsRunWhenFound() throws Exception {
            WorkflowRunEntity run = createAndSaveRun(testWorkflow, TENANT_ID, "public-run-id-123");

            mockMvc.perform(get("/api/workflows/runs/{runIdPublic}", "public-run-id-123")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("public-run-id-123"));
            // tenantId is @JsonIgnore on WorkflowRunSummary - not part of the response
        }
    }

    @Nested
    @DisplayName("GET /api/workflows/{workflowId}/runs/latest")
    class GetLatestRun {

        @Test
        @DisplayName("Returns 404 when no runs exist")
        void returns404WhenNoRuns() throws Exception {
            mockMvc.perform(get("/api/workflows/{workflowId}/runs/latest", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WorkflowEntity createAndSaveWorkflow(String tenantId, String name) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(tenantId);
        workflow.setName(name);
        workflow.setDescription("Test description");
        workflow.setVersion("1.0.0");
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setCreatedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        workflow.setPlan(createMinimalPlanData());
        // V263 OrgScopedEntity NOT NULL - stamp before save. @BeforeEach runs
        // outside an HTTP request, so the listener can't auto-fill from header.
        // Reuse tenantId as org id for parity.
        workflow.setOrganizationId(tenantId);
        return workflowRepository.save(workflow);
    }

    private WorkflowRunEntity createAndSaveRun(WorkflowEntity workflow, String tenantId, String runIdPublic) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(workflow);
        run.setTenantId(tenantId);
        run.setRunIdPublic(runIdPublic);
        run.setStatus(RunStatus.PENDING);
        run.setStartedAt(Instant.now());
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        // V263 - WorkflowRunEntity is OrgScopedEntity too. Reuse tenantId as org id.
        run.setOrganizationId(tenantId);
        return workflowRunRepository.save(run);
    }

    private Map<String, Object> createMinimalPlanData() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", "Test Plan");
        plan.put("description", "Minimal test plan");
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }
}
