package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationExecuteModule} - stop_run action.
 *
 * <p>Pins that stopping an APPLICATION run goes through the same shared handler as
 * the workflow tool (identical behaviour on both tools) and that a restricted agent
 * cannot stop a run belonging to an application outside its approved list.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationExecuteModule - stop_run")
class ApplicationExecuteModuleStopRunTest {

    private static final String TENANT_ID = "tenant-app-stop";
    private static final String RUN_ID = "run-app-1";

    @Mock WorkflowRepository workflowRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock RunStopToolHandler runStopToolHandler;

    private ApplicationExecuteModule module;
    private UUID publicationId;

    @BeforeEach
    void setUp() {
        module = new ApplicationExecuteModule(workflowRepository, agentWorkflowFireService,
                conversationEventPublisher,
                new com.apimarketplace.orchestrator.services.ApplicationLifecycleService(workflowRepository),
                workflowRunRepository, runStopToolHandler);
        publicationId = UUID.randomUUID();
    }

    /**
     * The run and its workflow, wired the way production sees them: the run holds a LAZY
     * workflow whose id is safe to read, and the allow-list check re-reads the workflow by
     * that id rather than dereferencing the proxy (open-in-view is off in this path).
     */
    private WorkflowRunEntity run(String tenantId, UUID sourcePublicationId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(tenantId);
        workflow.setSourcePublicationId(sourcePublicationId);
        lenient().when(workflowRepository.findById(workflow.getId())).thenReturn(Optional.of(workflow));
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(tenantId);
        run.setStatus(RunStatus.RUNNING);
        run.setWorkflow(workflow);
        return run;
    }

    private ToolExecutionContext context(List<String> allowedApplicationIds) {
        Map<String, Object> credentials = allowedApplicationIds == null
                ? Map.of()
                : Map.of("allowedApplicationIds", allowedApplicationIds);
        return new ToolExecutionContext(TENANT_ID, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private ToolExecutionResult stopRun(Map<String, Object> params, ToolExecutionContext context) {
        Optional<ToolExecutionResult> result = module.execute("stop_run", params, TENANT_ID, context);
        assertThat(result).isPresent();
        return result.get();
    }

    @Test
    @DisplayName("no run_id and no caller run: the agent is told how to name a run")
    void missingTargetIsRejected() {
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(null);
        when(runStopToolHandler.noTargetMessage("application")).thenReturn("run_id is required for stop_run.");

        ToolExecutionResult result = stopRun(Map.of(), context(null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verify(workflowRunRepository, never()).findByRunIdPublic(any());
    }

    /**
     * stop_run is destructive, and it is WRITE only by being absent from
     * ToolAccessControl's application READ set. Pin it so nobody "tidies" it in there.
     */
    @Test
    @DisplayName("a read-mode agent is DENIED stop_run and never reaches the run lookup")
    void readModeAgentCannotStopARun() {
        ToolExecutionContext readOnly = new ToolExecutionContext(TENANT_ID,
                Map.of("applicationAccessMode", "read"), Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID), readOnly);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verify(workflowRunRepository, never()).findByRunIdPublic(any());
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a run_id that is not a run id string is refused, never taken as a self-abort")
    void unusableRunIdIsRefused() {
        when(runStopToolHandler.resolveTargetRunId(any(), any()))
                .thenReturn(RunStopToolHandler.BAD_RUN_ID);
        when(runStopToolHandler.badRunIdMessage(any())).thenReturn("run_id must be a run id string");

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("run_id", 42);
        ToolExecutionResult result = stopRun(params, context(null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        verify(workflowRunRepository, never()).findByRunIdPublic(any());
    }

    @Test
    @DisplayName("a run of another tenant is invisible: not found, and it is NOT stopped")
    void crossTenantRunIsNotStopped() {
        WorkflowRunEntity foreign = run("someone-else", publicationId);
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(foreign));

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID), context(null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a restricted agent cannot stop a run of an application outside its approved list")
    void allowListBlocksForeignApplication() {
        WorkflowRunEntity foreignApp = run(TENANT_ID, publicationId);
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(foreignApp));

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID),
                context(List.of(UUID.randomUUID().toString())));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verify(runStopToolHandler, never()).stop(any(), any(), any(), any());
    }

    /**
     * Self-abort must survive the allow-list here too, and this tool has a second reason:
     * a plain workflow run has no source publication at all, so the list could never match.
     */
    @Test
    @DisplayName("a restricted agent CAN still abort the run it is executing inside")
    void allowListDoesNotBlockSelfAbort() {
        WorkflowRunEntity run = run(TENANT_ID, null);
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runStopToolHandler.stop(eq(run), any(), any(), eq("application")))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "CANCELLED", "self", true)));

        // The caller runs inside RUN_ID: the credential the platform stamps on an agent
        // executing inside a run (the own-run rule now lives in ApplicationRunAllowList).
        ToolExecutionResult result = stopRun(Map.of(), new ToolExecutionContext(TENANT_ID,
                Map.of("allowedApplicationIds", List.of(UUID.randomUUID().toString()),
                        "__workflowRunId__", RUN_ID),
                Map.of(), Set.of(), null, null, null, null));

        assertThat(result.success()).isTrue();
        verify(runStopToolHandler).stop(eq(run), any(), any(), eq("application"));
    }

    @Test
    @DisplayName("a restricted agent CAN stop a run of an application in its approved list")
    void allowListAcceptsApprovedApplication() {
        WorkflowRunEntity run = run(TENANT_ID, publicationId);
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runStopToolHandler.stop(eq(run), any(), any(), eq("application")))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "CANCELLED")));

        ToolExecutionResult result = stopRun(Map.of("run_id", RUN_ID),
                context(List.of(publicationId.toString())));

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("an unrestricted agent's stop is handed to the shared handler with the application tool name")
    void unrestrictedStopIsDelegatedToHandler() {
        WorkflowRunEntity run = run(TENANT_ID, publicationId);
        Map<String, Object> params = Map.of("run_id", RUN_ID, "reason", "wrong input");
        when(runStopToolHandler.resolveTargetRunId(any(), any())).thenReturn(RUN_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(runStopToolHandler.stop(eq(run), eq(params), any(), eq("application")))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "CANCELLED")));

        ToolExecutionResult result = stopRun(params, context(null));

        assertThat(result.success()).isTrue();
        verify(runStopToolHandler).stop(eq(run), eq(params), any(), eq("application"));
    }
}
