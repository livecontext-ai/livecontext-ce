package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService;
import com.apimarketplace.orchestrator.services.resume.StepRerunService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;
import com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderActionConfig;
import com.apimarketplace.publication.client.PublicationClient;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent-facing entry point for "restart this run from one node".
 *
 * <p>What matters here is that the agent is never left guessing: the run must be scoped and
 * allow-listed like every other run action, a refusal has to say what to do instead, and the
 * response has to state whether the replay actually finished, because a rerun that YIELDED and
 * one that ran to completion both come back with success=true.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule - restart_from_node")
class WorkflowCrudModuleRestartFromNodeTest {

    private static final String TENANT_ID = "tenant-restart";
    private static final String RUN_ID = "run-restart-1";
    private static final String NODE = "mcp:fetch_data";
    private static final String TRIGGER = "trigger:start";

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock AgentCancellationProbe cancellationProbe;
    @Mock RunStopToolHandler runStopToolHandler;
    @Mock StepRerunService stepRerunService;
    @Mock AutoRestartExecutionService autoRestartExecutionService;

    private WorkflowCrudModule module;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe, runStopToolHandler, stepRerunService, autoRestartExecutionService);
        workflowId = UUID.randomUUID();
    }

    private WorkflowRunEntity run(String tenantId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(tenantId);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(tenantId);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setWorkflow(workflow);
        return run;
    }

    private ToolExecutionContext context(List<String> allowedWorkflowIds) {
        Map<String, Object> credentials = allowedWorkflowIds == null
                ? Map.of()
                : Map.of("allowedWorkflowIds", allowedWorkflowIds);
        return new ToolExecutionContext(TENANT_ID, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private ToolExecutionResult restart(Map<String, Object> params, ToolExecutionContext ctx) {
        Optional<ToolExecutionResult> result = module.execute("restart_from_node", params, TENANT_ID, ctx);
        assertThat(result).isPresent();
        return result.get();
    }

    private void rerunReturns(AutoRestartExecutionService.Outcome outcome) {
        rerunReturns(outcome, 4);
    }

    /** @param epoch the epoch the service reports back, which is the one that actually ran */
    private void rerunReturns(AutoRestartExecutionService.Outcome outcome, int epoch) {
        when(stepRerunService.rerunFromStep(eq(RUN_ID), eq(NODE), eq(false), any()))
                .thenReturn(new StepRerunService.RerunResult(
                        RUN_ID, NODE, epoch, 1, Set.of(NODE, "mcp:downstream"), Set.of(NODE),
                        "running", 42L, TRIGGER));
        when(autoRestartExecutionService.resumeAfterRerun(anyString(), any(), anyString(), anyInt()))
                .thenReturn(outcome);
    }

    @Test
    @DisplayName("The action is reachable: handled by the module and listed for the agent")
    void actionIsReachable() {
        assertThat(module.canHandle("restart_from_node")).isTrue();
        // Listing it is what makes it callable at all; a handled-but-unlisted action is dead code.
        assertThat(WorkflowBuilderActionConfig.PRIMARY_ACTIONS).contains("restart_from_node");
    }

    @Test
    @DisplayName("It needs the user's go-ahead in chat, like every action that STARTS work")
    void requiresUserAuthorization() {
        // It re-executes nodes unattended, with the same spend and side effects as a fresh fire.
        assertThat(ToolAuthorizationPolicy.requires("workflow", "restart_from_node")).isTrue();
    }

    @Test
    @DisplayName("Missing run_id: the agent is told where to find one, not just refused")
    void missingRunIdIsRejected() {
        ToolExecutionResult result = restart(Map.of("node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("action='runs'");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Missing node: the message says which key shape to pass")
    void missingNodeIsRejected() {
        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("get_run");
        assertThat(result.error()).contains("mcp:fetch_data");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A blank node is treated as missing, not passed through as a step id")
    void blankNodeIsRejected() {
        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", "   "), context(null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Another tenant's run reads as not found, never as forbidden")
    void otherTenantRunIsNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run("someone-else")));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A workflow outside the agent's allow-list is refused before anything is reset")
    void workflowOutsideAllowListIsRefused() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE),
                context(List.of(UUID.randomUUID().toString())));

        assertThat(result.success()).isFalse();
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Happy path: reports what was replayed, in which attempt, and that it settled")
    void restartsAndReportsWhatHappened() {
        WorkflowRunEntity entity = run(TENANT_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(entity));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED);

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isTrue();
        Map<String, Object> data = asMap(result.data());
        assertThat(data.get("restarted_from")).isEqualTo(NODE);
        assertThat(data.get("outcome")).isEqualTo("QUIESCED");
        assertThat(data.get("epoch")).isEqualTo(4);
        // The attempt counter is what lets the agent tell this replay's output from the first run's.
        assertThat(data.get("attempt")).isEqualTo(1);
        Set<String> replayed = ((Set<?>) data.get("replayed_nodes")).stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        assertThat(replayed).containsExactlyInAnyOrder(NODE, "mcp:downstream");
        assertThat(String.valueOf(data.get("summary"))).contains("get_run");
    }

    @Test
    @DisplayName("Carries the run visualization (plan version, name loaded apart) without touching the lazy workflow's fields")
    void carriesRunVisualizationForThePage() {
        // In production run.getWorkflow() is an uninitialised proxy (LAZY, no session here): only
        // its id may be read. A getName() on it throws after the replay already ran.
        WorkflowEntity lazyWorkflow = org.mockito.Mockito.mock(WorkflowEntity.class);
        when(lazyWorkflow.getId()).thenReturn(workflowId);
        lenient().when(lazyWorkflow.getName()).thenThrow(new org.hibernate.LazyInitializationException("no session"));
        lenient().when(lazyWorkflow.getTenantId()).thenReturn(TENANT_ID);
        WorkflowRunEntity entity = run(TENANT_ID);
        entity.setWorkflow(lazyWorkflow);
        entity.setPlanVersion(13);
        WorkflowEntity loaded = new WorkflowEntity();
        loaded.setId(workflowId);
        loaded.setName("Gmail Triage");
        when(workflowService.getWorkflow(workflowId)).thenReturn(Optional.of(loaded));
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(entity));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED);

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isTrue();
        assertThat(asMap(result.metadata().get("visualization")))
                .containsEntry("type", "workflow_run")
                .containsEntry("id", workflowId.toString())
                .containsEntry("title", "Gmail Triage")
                .containsEntry("runId", RUN_ID)
                .containsEntry("planVersion", 13);
    }

    @Test
    @DisplayName("A replay that ran stays a success when its visualization cannot be built")
    void replayStaysSuccessfulWhenVisualizationFails() {
        WorkflowRunEntity entity = run(TENANT_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(entity));
        when(workflowService.getWorkflow(workflowId)).thenThrow(new IllegalStateException("db down"));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED);

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isTrue();
        assertThat(asMap(result.metadata().get("visualization"))).containsEntry("title", "Workflow");
    }

    @Test
    @DisplayName("A yield is reported as unfinished work, with the action that unblocks it")
    void yieldTellsTheAgentItIsNotDone() {
        // success=true would otherwise read as "the replay is done" while the run sits on an
        // approval nobody resolves.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.YIELDED);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(data.get("outcome")).isEqualTo("YIELDED");
        assertThat(String.valueOf(data.get("summary"))).contains("resolve_approval");
    }

    @Test
    @DisplayName("A stepped run says nothing was executed, instead of implying a replay ran")
    void steppedRunSaysNothingRan() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.STEP_BY_STEP);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(String.valueOf(data.get("summary"))).contains("nothing was executed");
    }

    @Test
    @DisplayName("Hitting the safety limit is reported as INCOMPLETE, not as a finished replay")
    void waveCapIsReportedAsIncomplete() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.WAVE_CAP);

        Map<String, Object> data = asMap(restart(Map.of("run_id", RUN_ID, "node", NODE), context(null)).data());

        assertThat(String.valueOf(data.get("summary"))).contains("INCOMPLETE");
    }

    @Test
    @DisplayName("A node that cannot be restarted surfaces the backend's own explanation")
    void refusalKeepsTheBackendExplanation() {
        // The service refuses a node that never settled; swallowing that message would leave the
        // agent retrying the same call forever.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        when(stepRerunService.rerunFromStep(eq(RUN_ID), eq(NODE), eq(false), any()))
                .thenThrow(new IllegalStateException("Cannot rerun step mcp:fetch_data: not in rerunnable state."));

        ToolExecutionResult result = restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("not in rerunnable state");
    }

    // ====================== epoch: which fire of the run to replay ======================

    @Test
    @DisplayName("Omitting epoch replays the run's most recent fire (null reaches the service)")
    void omittedEpochMeansTheLatestFire() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED);

        restart(Map.of("run_id", RUN_ID, "node", NODE), context(null));

        // null, not 0: epoch 0 is a real epoch, so it must not double as "unspecified".
        verify(stepRerunService).rerunFromStep(RUN_ID, NODE, false, null);
    }

    @Test
    @DisplayName("A chosen epoch reaches the service instead of being dropped")
    void chosenEpochIsForwarded() {
        // Regression: the action used to read only run_id and node, so an epoch passed by the
        // agent was silently ignored and the replay landed on the latest fire - the caller saw a
        // success carrying a different epoch than the one it asked to repair.
        WorkflowRunEntity entity = run(TENANT_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(entity));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED, 11);

        Map<String, Object> data = asMap(
                restart(Map.of("run_id", RUN_ID, "node", NODE, "epoch", 11), context(null)).data());

        verify(stepRerunService).rerunFromStep(RUN_ID, NODE, false, 11);
        assertThat(data.get("epoch")).isEqualTo(11);
    }

    @Test
    @DisplayName("The drive after the replay runs in the chosen epoch, not the run's latest")
    void chosenEpochDrivesTheReplay() {
        // The wave loop executes nodes with an explicit (epoch, trigger); handing it the wrong
        // epoch would reset one fire and execute another.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED, 11);

        restart(Map.of("run_id", RUN_ID, "node", NODE, "epoch", 11), context(null));

        verify(autoRestartExecutionService).resumeAfterRerun(RUN_ID, Set.of(NODE), TRIGGER, 11);
    }

    @Test
    @DisplayName("An epoch sent as a string still counts (models emit numbers as text)")
    void numericStringEpochIsAccepted() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        rerunReturns(AutoRestartExecutionService.Outcome.QUIESCED, 11);

        restart(Map.of("run_id", RUN_ID, "node", NODE, "epoch", "11"), context(null));

        verify(stepRerunService).rerunFromStep(RUN_ID, NODE, false, 11);
    }

    @Test
    @DisplayName("An unusable epoch is REFUSED, never silently dropped")
    void unusableEpochIsRefused() {
        // Dropping it is the worst outcome available: the replay would redo a fire that was
        // already correct, report success, and leave the broken fire exactly as it was.
        // Refused on the parameter alone, before the run is even loaded - same as a missing node.
        ToolExecutionResult result = restart(
                Map.of("run_id", RUN_ID, "node", NODE, "epoch", "latest"), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("epoch must be a number");
        assertThat(result.error()).contains("Omit it");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A fractional epoch is refused, not truncated into a different real epoch")
    void fractionalEpochIsRefused() {
        // 11.9 truncated is 11, a real and DIFFERENT epoch: the call would succeed and repair
        // something the caller never named.
        ToolExecutionResult result = restart(
                Map.of("run_id", RUN_ID, "node", NODE, "epoch", 11.9), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("whole number");
        verify(stepRerunService, never()).rerunFromStep(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("An epoch the run cannot replay surfaces the backend's explanation")
    void unknownEpochKeepsTheBackendExplanation() {
        // The sentence is STUBBED here, so this test cannot notice the service rewording it -
        // it is quoted from StepRerunService.resolveRequestedEpoch and has to be kept in step
        // with it. The wording matters: the same refusal also covers a run whose epoch record
        // was never written, and that caller DOES see the epoch in the listing.
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run(TENANT_ID)));
        when(stepRerunService.rerunFromStep(eq(RUN_ID), eq(NODE), eq(false), any()))
                .thenThrow(new IllegalArgumentException("Epoch 99 cannot be restarted on its own for "
                        + "trigger trigger:start: this run kept no restorable record of it."));

        ToolExecutionResult result = restart(
                Map.of("run_id", RUN_ID, "node", NODE, "epoch", 99), context(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("no restorable record");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        assertThat(data).isInstanceOf(Map.class);
        return (Map<String, Object>) data;
    }
}
