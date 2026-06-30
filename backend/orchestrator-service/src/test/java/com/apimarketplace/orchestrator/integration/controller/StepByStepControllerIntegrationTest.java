package com.apimarketplace.orchestrator.integration.controller;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for StepByStepController and WorkflowRunController.
 * Tests step-by-step execution, pause/resume, and run state endpoints at /api/v2/workflows/dag.
 *
 * <p>Validates HTTP contract for step execution, execution mode management,
 * ready steps retrieval, pause/resume, and core node evaluation.</p>
 */
@DisplayName("StepByStepController Integration Tests")
class StepByStepControllerIntegrationTest extends BaseControllerIntegrationTest {

    @MockitoBean
    private WorkflowResumeService resumeService;

    @MockitoBean
    private TriggerClient triggerClient;

    @MockitoBean
    private CreditConsumptionClient creditClient;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @BeforeEach
    void setUp() {
        when(triggerClient.getTokensForWorkflow(any(UUID.class)))
            .thenReturn(Map.of());
        when(creditClient.checkCredits(anyString())).thenReturn(true);
        persistRun("run-123", TENANT_ID);
    }

    private WorkflowRunEntity persistRun(String runIdPublic, String tenantId) {
        Optional<WorkflowRunEntity> existing = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (existing.isPresent()) {
            return existing.get();
        }

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(tenantId);
        workflow.setName("StepByStep test workflow");
        workflow.setDescription("test");
        workflow.setVersion("1.0.0");
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setCreatedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", "Test Plan");
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        workflow.setPlan(plan);
        // V263 OrgScopedEntity NOT NULL - stamp before save. @BeforeEach runs
        // outside an HTTP request, so the listener can't auto-fill from header.
        workflow.setOrganizationId(tenantId);
        workflow = workflowRepository.save(workflow);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(workflow);
        run.setTenantId(tenantId);
        run.setRunIdPublic(runIdPublic);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        // V263 - WorkflowRunEntity is OrgScopedEntity too.
        run.setOrganizationId(tenantId);
        return workflowRunRepository.save(run);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(X_USER_ID, TENANT_ID)
                .header("X-Organization-ID", TENANT_ID);  // V263 OrgScopedEntity
    }

    // =========================================================================
    // Helper to create a WorkflowRunState
    // =========================================================================

    private WorkflowRunState createRunState(String runId, RunStatus status, Set<String> readySteps) {
        return new WorkflowRunState(
            runId,
            UUID.randomUUID().toString(),
            status,
            ExecutionMode.STEP_BY_STEP,
            Instant.now(),
            null,
            Map.of(),
            List.of(),
            List.of(),
            readySteps,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Map.of(),
            List.of()
        );
    }

    // =========================================================================
    // POST /runs/{runId}/step/{stepId}/execute - Execute Single Step
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/runs/{runId}/step/{stepId}/execute")
    class ExecuteSingleStep {

        @Test
        @DisplayName("Executes step successfully")
        void executesStepSuccessfully() throws Exception {
            StepExecutionResult stepResult = new StepExecutionResult(
                "mcp:step1", NodeStatus.COMPLETED, "Step executed",
                Map.of("result", "success"), 150L, null
            );
            WorkflowRunState runState = createRunState("run-123", RunStatus.RUNNING, Set.of("mcp:step2"));

            when(resumeService.executeSingleStep("run-123", "mcp:step1")).thenReturn(stepResult);
            when(resumeService.reconstructStateForApi("run-123")).thenReturn(runState);

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/step/{stepId}/execute",
                    "run-123", "mcp:step1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.stepId").value("mcp:step1"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.executionTime").value(150))
                .andExpect(jsonPath("$.readySteps").isArray());
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.executeSingleStep("nonexistent", "step1"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/step/{stepId}/execute",
                    "nonexistent", "step1")))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 400 when step is not ready")
        void returns400WhenStepNotReady() throws Exception {
            when(resumeService.executeSingleStep("run-123", "mcp:step_not_ready"))
                .thenThrow(new IllegalStateException("Step is not ready for execution"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/step/{stepId}/execute",
                    "run-123", "mcp:step_not_ready")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Step is not ready for execution"));
        }
    }

    // =========================================================================
    // GET /runs/{runId}/ready-steps - Get Ready Steps
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v2/workflows/dag/runs/{runId}/ready-steps")
    class GetReadySteps {

        @Test
        @DisplayName("Returns ready steps")
        void returnsReadySteps() throws Exception {
            when(resumeService.getReadySteps("run-123"))
                .thenReturn(Set.of("mcp:step1", "mcp:step2"));

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/ready-steps", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.readySteps").isArray())
                .andExpect(jsonPath("$.count").value(2));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.getReadySteps("nonexistent"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/ready-steps", "nonexistent")))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // GET /runs/{runId}/is-paused - Check Paused Status
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v2/workflows/dag/runs/{runId}/is-paused")
    class IsPaused {

        @Test
        @DisplayName("Returns paused status true")
        void returnsPausedTrue() throws Exception {
            when(resumeService.isPaused("run-123")).thenReturn(true);

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/is-paused", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.isPaused").value(true));
        }

        @Test
        @DisplayName("Returns paused status false")
        void returnsPausedFalse() throws Exception {
            when(resumeService.isPaused("run-123")).thenReturn(false);

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/is-paused", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.isPaused").value(false));
        }
    }

    // =========================================================================
    // POST /runs/{runId}/execution-mode - Set Execution Mode
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/runs/{runId}/execution-mode")
    class SetExecutionMode {

        @Test
        @DisplayName("Sets execution mode to step_by_step")
        void setsStepByStepMode() throws Exception {
            WorkflowRunState runState = createRunState("run-123", RunStatus.PAUSED, Set.of("mcp:step1"));

            doNothing().when(resumeService).setExecutionMode("run-123", ExecutionMode.STEP_BY_STEP);
            when(resumeService.reconstructStateForApi("run-123")).thenReturn(runState);

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/execution-mode", "run-123"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mode\": \"step_by_step\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.executionMode").value("step_by_step"));
        }

        @Test
        @DisplayName("Returns 400 when mode is missing")
        void returns400WhenModeMissing() throws Exception {
            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/execution-mode", "run-123"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing 'mode' in request body"));
        }
    }

    // =========================================================================
    // GET /runs/{runId}/execution-mode - Get Execution Mode
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v2/workflows/dag/runs/{runId}/execution-mode")
    class GetExecutionMode {

        @Test
        @DisplayName("Returns current execution mode")
        void returnsCurrentExecutionMode() throws Exception {
            when(resumeService.getExecutionMode("run-123"))
                .thenReturn(ExecutionMode.STEP_BY_STEP);

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/execution-mode", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.executionMode").value("step_by_step"))
                .andExpect(jsonPath("$.isStepByStep").value(true));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.getExecutionMode("nonexistent"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(get("/api/v2/workflows/dag/runs/{runId}/execution-mode", "nonexistent")))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // POST /runs/{runId}/start-step-by-step - Start in Step-by-Step Mode
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/runs/{runId}/start-step-by-step")
    class StartStepByStep {

        @Test
        @DisplayName("Starts workflow in step-by-step mode")
        void startsStepByStepMode() throws Exception {
            WorkflowRunState runState = createRunState("run-123", RunStatus.PAUSED, Set.of("trigger:start"));

            when(resumeService.startInStepByStepMode("run-123")).thenReturn(runState);

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/start-step-by-step", "run-123")
                    .contentType(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.executionMode").value("step_by_step"))
                .andExpect(jsonPath("$.readySteps").isArray())
                .andExpect(jsonPath("$.message").value("Workflow started in step-by-step mode"));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.startInStepByStepMode("nonexistent"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/start-step-by-step", "nonexistent")
                    .contentType(MediaType.APPLICATION_JSON)))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // Workflow Run Controller Tests - Pause/Resume
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/runs/{runId}/pause")
    class PauseWorkflow {

        @Test
        @DisplayName("Pauses running workflow")
        void pausesRunningWorkflow() throws Exception {
            WorkflowRunState pausedState = createRunState("run-123", RunStatus.PAUSED, Set.of("mcp:step1"));
            when(resumeService.pauseWorkflow("run-123")).thenReturn(pausedState);

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/pause", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.message").value("Workflow paused successfully"));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.pauseWorkflow("nonexistent"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/pause", "nonexistent")))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 400 when workflow cannot be paused")
        void returns400WhenCannotPause() throws Exception {
            when(resumeService.pauseWorkflow("run-123"))
                .thenThrow(new IllegalStateException("Workflow is not in RUNNING state"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/pause", "run-123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Workflow is not in RUNNING state"));
        }
    }

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/runs/{runId}/resume")
    class ResumeWorkflow {

        @Test
        @DisplayName("Resumes paused workflow")
        void resumesPausedWorkflow() throws Exception {
            WorkflowRunState resumedState = createRunState("run-123", RunStatus.RUNNING, Set.of("mcp:step2"));
            when(resumeService.resumeWorkflow("run-123")).thenReturn(resumedState);

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/resume", "run-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.message").value("Workflow resumed successfully"));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            when(resumeService.resumeWorkflow("nonexistent"))
                .thenThrow(new IllegalArgumentException("Run not found"));

            mockMvc.perform(authenticated(post("/api/v2/workflows/dag/runs/{runId}/resume", "nonexistent")))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // GET /runs/{runId}/state - Get Run State
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v2/workflows/dag/runs/{runId}/state")
    class GetRunState {

        @Test
        @DisplayName("Returns run state (default lean variant)")
        void returnsRunState() throws Exception {
            // Default GET /state (no ?full=true) routes to reconstructStateForApi -
            // the lean variant that skips storage round-trips for non-rendered aliases.
            // Controller does an owner-check via findByRunIdPublic before delegating to
            // the service, so the run must exist in DB and belong to TENANT_ID.
            persistRun("run-123", TENANT_ID);
            WorkflowRunState runState = createRunState("run-123", RunStatus.RUNNING, Set.of("mcp:step2"));
            when(resumeService.reconstructStateForApi("run-123")).thenReturn(runState);

            mockMvc.perform(get("/api/v2/workflows/dag/runs/{runId}/state", "run-123")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"))
                .andExpect(jsonPath("$.status").exists());
        }

        @Test
        @DisplayName("Returns full state when ?full=true (engine variant)")
        void returnsFullRunStateWhenFullParam() throws Exception {
            // ?full=true routes to reconstructState (FULL) - used by E2E OutputVerifier.
            persistRun("run-123", TENANT_ID);
            WorkflowRunState runState = createRunState("run-123", RunStatus.RUNNING, Set.of("mcp:step2"));
            when(resumeService.reconstructState("run-123")).thenReturn(runState);

            mockMvc.perform(get("/api/v2/workflows/dag/runs/{runId}/state?full=true", "run-123")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-123"));
        }

        @Test
        @DisplayName("Returns 404 when run not found")
        void returns404WhenRunNotFound() throws Exception {
            // Controller short-circuits with 404 at the owner-check stage - the
            // resumeService mock is never reached.
            mockMvc.perform(get("/api/v2/workflows/dag/runs/{runId}/state", "nonexistent")
                    .header(X_USER_ID, TENANT_ID)
                    .header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isNotFound());
        }
    }
}
