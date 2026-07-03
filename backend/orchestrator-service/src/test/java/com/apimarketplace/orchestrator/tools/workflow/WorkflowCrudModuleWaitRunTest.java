package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowCrudModule} - wait_run action (blocking wait
 * until a run leaves the in-flight states, with timeout and caller-cancel).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule - wait_run")
class WorkflowCrudModuleWaitRunTest {

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock AgentCancellationProbe cancellationProbe;

    private WorkflowCrudModule module;
    private static final String TENANT_ID = "tenant-wait";
    private static final String RUN_ID = "run-wait-1";

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe);
        // @Value fields are not injected outside Spring: mirror the production defaults.
        module.waitRunDefaultTimeoutSeconds = 120;
        module.waitRunMaxTimeoutSeconds = 240;
        // Shrink wait cadence so tests run in milliseconds, not seconds.
        module.waitRunPollIntervalMs = 10L;
        module.waitRunSliceMs = 5L;
    }

    private WorkflowRunEntity runWithStatus(RunStatus status) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(TENANT_ID);
        workflow.setPlan(Map.of("triggers", List.of()));
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setStatus(status);
        run.setWorkflow(workflow);
        return run;
    }

    private ToolExecutionResult waitRun(Map<String, Object> params) {
        Optional<ToolExecutionResult> result = module.execute("wait_run", params, TENANT_ID, null);
        assertThat(result).isPresent();
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    @Test
    @DisplayName("canHandle: wait_run is a handled action")
    void canHandleWaitRun() {
        assertThat(module.canHandle("wait_run")).isTrue();
    }

    /**
     * The 240s production default keeps a single blocking wait under the 5-minute
     * silence watchdogs (bridge inactivity kill + agent loop inactivity watchdog).
     * setUp mirrors the values by hand, so without this pin a regression raising
     * the annotation default past the watchdog window would pass the suite green.
     */
    @Test
    @DisplayName("production defaults stay 120/240 (max under the 5-min silence watchdogs)")
    void productionDefaultsStayUnderSilenceWatchdogs() throws Exception {
        var maxField = WorkflowCrudModule.class.getDeclaredField("waitRunMaxTimeoutSeconds");
        var maxValue = maxField.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertThat(maxValue).isNotNull();
        assertThat(maxValue.value()).isEqualTo("${workflow.wait-run.max-timeout-seconds:240}");

        var defaultField = WorkflowCrudModule.class.getDeclaredField("waitRunDefaultTimeoutSeconds");
        var defaultValue = defaultField.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertThat(defaultValue).isNotNull();
        assertThat(defaultValue.value()).isEqualTo("${workflow.wait-run.default-timeout-seconds:120}");
    }

    @Test
    @DisplayName("missing run_id -> MISSING_PARAMETER, no repository access")
    void missingRunId() {
        ToolExecutionResult r = waitRun(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("timeout_seconds=0 -> INVALID_PARAMETER_VALUE naming the 1..max range")
    void timeoutTooSmall() {
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 0));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(r.error()).contains("between 1 and 240");
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("timeout_seconds above the max (241) -> INVALID_PARAMETER_VALUE telling the agent to re-call wait_run")
    void timeoutTooLarge() {
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 241));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(r.error()).contains("call wait_run again");
    }

    @Test
    @DisplayName("timeout_seconds at the max (240) is accepted")
    void timeoutAtMaxAccepted() {
        WorkflowRunEntity run = runWithStatus(RunStatus.COMPLETED);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "COMPLETED"));

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 240));

        assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("unparseable timeout_seconds is an explicit error, never a silent fall-through to the default")
    void unparseableTimeoutRejected() {
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", "soon"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(r.error()).contains("'soon'");
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("absent timeout_seconds uses the configured default (proven by the timeout firing at the default)")
    void absentTimeoutUsesDefault() {
        module.waitRunDefaultTimeoutSeconds = 1; // observable: the wait times out after ~1s
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(running));
        when(agentWorkflowFireService.buildRunMacroReport(eq(running), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "RUNNING"));
        when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);

        long start = System.currentTimeMillis();
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("timed_out")).isEqualTo(true);
        assertThat(elapsed).isBetween(900L, 10_000L);
    }

    @Test
    @DisplayName("unknown run -> RESOURCE_NOT_FOUND")
    void runNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("run of another tenant is masked as RESOURCE_NOT_FOUND (same as get_run)")
    void runOutOfScopeMaskedAsNotFound() {
        WorkflowRunEntity run = runWithStatus(RunStatus.RUNNING);
        run.setTenantId("someone-else");
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(agentWorkflowFireService);
    }

    @Test
    @DisplayName("org-scoped run is masked from a personal-workspace caller (org half of isInStrictScope)")
    void orgScopedRunMaskedFromPersonalCaller() {
        WorkflowRunEntity run = runWithStatus(RunStatus.RUNNING);
        run.setOrganizationId("org-x"); // same tenant, but the row belongs to an org workspace
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(agentWorkflowFireService);
    }

    @Test
    @DisplayName("allow-list: wait_run on a workflow outside allowedWorkflowIds is denied without waiting")
    void outOfAllowListDenied() {
        WorkflowRunEntity run = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        ToolExecutionContext ctx = new ToolExecutionContext(TENANT_ID,
                Map.of("allowedWorkflowIds", List.of(UUID.randomUUID().toString())),
                Map.of(), java.util.Set.of(), null, null, null, null);

        Optional<ToolExecutionResult> result = module.execute("wait_run", Map.of("run_id", RUN_ID), TENANT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().error()).contains("approved workflow list");
        verifyNoInteractions(agentWorkflowFireService);
    }

    @Test
    @DisplayName("run already terminal -> returns immediately with the macro report, no polling")
    void alreadyTerminalReturnsImmediately() {
        WorkflowRunEntity run = runWithStatus(RunStatus.COMPLETED);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        Map<String, Object> report = new LinkedHashMap<>(Map.of("run_id", RUN_ID, "status", "COMPLETED"));
        when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID))).thenReturn(report);

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("status")).isEqualTo("completed");
        assertThat(data(r).get("timed_out")).isEqualTo(false);
        assertThat(data(r).get("run")).isEqualTo(report);
        // No wait happened: single fetch, cancellation never probed.
        verify(workflowRunRepository, times(1)).findByRunIdPublic(RUN_ID);
        verifyNoInteractions(cancellationProbe);
    }

    @Test
    @DisplayName("PAUSED (needs input) ends the wait like a terminal state - the agent must act, not sleep")
    void pausedEndsWait() {
        WorkflowRunEntity run = runWithStatus(RunStatus.PAUSED);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "PAUSED"));

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID));

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("status")).isEqualTo("paused");
        assertThat(data(r).get("timed_out")).isEqualTo(false);
        verify(workflowRunRepository, times(1)).findByRunIdPublic(RUN_ID);
    }

    @Test
    @DisplayName("RUNNING then COMPLETED -> waits through the transition and returns the final report")
    void runningThenCompleted() {
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        WorkflowRunEntity completed = runWithStatus(RunStatus.COMPLETED);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(running), Optional.of(completed));
        Map<String, Object> report = Map.of("run_id", RUN_ID, "status", "COMPLETED");
        when(agentWorkflowFireService.buildRunMacroReport(eq(completed), any(), eq(TENANT_ID))).thenReturn(report);
        when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 5));

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("status")).isEqualTo("completed");
        assertThat(data(r).get("timed_out")).isEqualTo(false);
        assertThat(data(r)).doesNotContainKey("next_action");
        assertThat(data(r).get("run")).isEqualTo(report);
        verify(workflowRunRepository, times(2)).findByRunIdPublic(RUN_ID);
    }

    @Test
    @DisplayName("timeout while still RUNNING -> success with timed_out=true + next_action, run report included")
    void timeoutWhileRunning() {
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(running));
        when(agentWorkflowFireService.buildRunMacroReport(eq(running), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "RUNNING"));
        when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 1));

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("timed_out")).isEqualTo(true);
        assertThat(data(r).get("status")).isEqualTo("running");
        assertThat((String) data(r).get("next_action")).contains("wait_run").contains("get_run");
        // Polled repeatedly during the 1s window (initial + at least one refresh).
        verify(workflowRunRepository, atLeast(2)).findByRunIdPublic(RUN_ID);
    }

    @Test
    @DisplayName("caller stop mid-wait -> returns promptly with cancelled=true and a wrap-up note, not timed_out")
    void cancelledMidWait() {
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(running));
        when(agentWorkflowFireService.buildRunMacroReport(eq(running), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "RUNNING"));
        when(cancellationProbe.isCallerCancelled(any())).thenReturn(true);

        long start = System.currentTimeMillis();
        // Max budget (240s): only the cancel can end this fast.
        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 240));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.success()).isTrue();
        assertThat(data(r).get("cancelled")).isEqualTo(true);
        assertThat(data(r).get("timed_out")).isEqualTo(false);
        assertThat((String) data(r).get("note")).contains("stopped");
        assertThat(elapsed).isLessThan(5_000L);
    }

    @Test
    @DisplayName("run deleted mid-wait -> RESOURCE_NOT_FOUND naming the disappearance")
    void runDisappearsMidWait() {
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(running), Optional.empty());
        when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);

        ToolExecutionResult r = waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 5));

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        assertThat(r.error()).contains("disappeared");
        verify(agentWorkflowFireService, never()).buildRunMacroReport(any(), any(), any());
    }

    @Test
    @DisplayName("read-mode agent (__workflowAccessMode__='read') may call wait_run through the module's real access gate")
    void readModeAllowsWaitRun() {
        WorkflowRunEntity run = runWithStatus(RunStatus.COMPLETED);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                .thenReturn(Map.of("status", "COMPLETED"));
        // The module's execute() runs ToolAccessControl.checkWriteAccess on this credential
        // key: a regression dropping wait_run from READ_ACTIONS turns this into a denial.
        ToolExecutionContext ctx = new ToolExecutionContext(TENANT_ID,
                Map.of("__workflowAccessMode__", "read"), Map.of(), java.util.Set.of(), null, null, null, null);

        Optional<ToolExecutionResult> result = module.execute("wait_run", Map.of("run_id", RUN_ID), TENANT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }

    @Test
    @DisplayName("thread interrupt mid-wait -> EXECUTION_FAILED and the interrupt flag is restored")
    void interruptMidWait() throws Exception {
        WorkflowRunEntity running = runWithStatus(RunStatus.RUNNING);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(running));
        org.mockito.Mockito.lenient().when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);
        module.waitRunPollIntervalMs = 5_000L; // long slice: only the interrupt can end this fast
        module.waitRunSliceMs = 5_000L;

        java.util.concurrent.atomic.AtomicReference<ToolExecutionResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean flagRestored = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            resultRef.set(waitRun(Map.of("run_id", RUN_ID, "timeout_seconds", 240)));
            flagRestored.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        ToolExecutionResult r = resultRef.get();
        assertThat(r).isNotNull();
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).containsIgnoringCase("interrupted");
        assertThat(flagRestored).as("interrupt flag must be restored").isTrue();
    }
}
