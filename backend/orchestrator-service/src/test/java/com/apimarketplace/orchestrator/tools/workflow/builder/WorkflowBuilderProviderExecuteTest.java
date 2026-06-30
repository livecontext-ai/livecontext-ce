package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code workflow(action='execute')} path in {@link WorkflowBuilderProvider}.
 *
 * Covers:
 * - ID resolution (direct param, alias, session fallback, missing)
 * - Workflow validation (not found, tenant mismatch, no triggers)
 * - Trigger resolution error propagation
 * - Successful execution with various param combinations
 * - Exception handling (runtime errors → failure result)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - execute action")
class WorkflowBuilderProviderExecuteTest {

    // ── infrastructure mocks ───────────────────────────────────────────────
    @Mock WorkflowBuilderSessionManager sessionManager;
    @Mock WorkflowBuilderResultEnricher resultEnricher;
    @Mock WorkflowDraftAutoSaver draftAutoSaver;
    @Mock WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock WorkflowBuilderLogger buildLogger;
    @Mock WorkflowCrudModule crudModule;
    @Mock WorkflowManagementService workflowService;
    @Mock InterfaceClient interfaceClient;
    @Mock NodeTypeSearchService nodeTypeSearchService;
    @Mock NodeLibraryService nodeLibraryService;
    @Mock NodeParamsValidator nodeParamsValidator;
    @Mock WorkflowHelpProvider workflowHelpProvider;
    @Mock WorkflowBuilderCreator creator;
    @Mock WorkflowBuilderConnectionManager connectionManager;
    @Mock WorkflowBuilderModifier modifier;
    @Mock WorkflowBuilderViewer viewer;
    @Mock WorkflowBuilderLoader loader;
    @Mock WorkflowBuilderTableOperations tableOperations;
    @Mock WorkflowBuilderPlanExporter planExporter;
    @Mock WorkflowBuilderHelpModule helpModule;
    @Mock WorkflowExecutionService executionService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService runSignalResolution;
    @Mock com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;
    @Mock com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;

    private WorkflowBuilderProvider provider;

    private static final String TENANT_ID = "tenant-execute-test";
    private static final String WF_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final ToolExecutionContext CTX = ToolExecutionContext.of(TENANT_ID);

    @BeforeEach
    void setUp() {
        provider = new WorkflowBuilderProvider(
            sessionManager, resultEnricher, draftAutoSaver, toolDefinitionFactory, buildLogger,
            crudModule, workflowService, interfaceClient, nodeTypeSearchService, nodeLibraryService,
            nodeParamsValidator, workflowHelpProvider, creator, connectionManager, modifier, viewer,
            loader, tableOperations, planExporter, helpModule, executionService, workflowRunRepository,
            agentWorkflowFireService, runSignalResolution, planVersionService, productionRunResolver,
            new com.apimarketplace.orchestrator.config.AgentDefaultsConfig(),
            conversationEventPublisher
        );
        // Pass-through for addSessionSnapshot so tests see the actual result
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Params with action=execute and an explicit id. */
    private Map<String, Object> params(String... extras) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "execute");
        p.put("id", WF_ID);
        for (int i = 0; i + 1 < extras.length; i += 2) p.put(extras[i], extras[i + 1]);
        return p;
    }

    /** Minimal WorkflowEntity with a manual trigger plan. */
    private WorkflowEntity manualTriggerWorkflow() {
        WorkflowEntity e = new WorkflowEntity();
        e.setId(UUID.fromString(WF_ID));
        e.setTenantId(TENANT_ID);
        e.setName("Execute Test Workflow");

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "start");
        trigger.put("label", "Start");
        trigger.put("type", "manual");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(trigger));
        plan.put("mcps", List.of());
        plan.put("cores", List.of());
        plan.put("edges", List.of());
        e.setPlan(plan);
        return e;
    }

    /** Wire up a successful full-cycle mock execution. */
    private void stubSuccessfulExecution(WorkflowEntity entity, Trigger trigger,
                                          WorkflowRunEntity run,
                                          Map<String, Object> dataInputs) {
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        lenient().when(agentWorkflowFireService.resolveTrigger(any(), any())).thenReturn(trigger);
        lenient().when(agentWorkflowFireService.createRun(any(), any(), eq(dataInputs), eq(TENANT_ID))).thenReturn(run);
        TriggerExecutionResult tr = TriggerExecutionResult.success(
                "run-1", "trigger:start", TriggerType.MANUAL, Set.of(), 0);
        lenient().when(agentWorkflowFireService.fire(run, trigger, dataInputs)).thenReturn(tr);
        lenient().when(agentWorkflowFireService.buildResult(eq(run), eq(tr), any(), any(), any()))
                .thenReturn(Map.of("status", "COMPLETED", "run_id", "run-1"));
        lenient().when(run.getRunIdPublic()).thenReturn("run-1");
    }

    // ── ID resolution ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing id param and no session → failure mentioning id")
    void missingId_noSession_failure() {
        Map<String, Object> p = Map.of("action", "execute");
        // Session not found → error
        when(sessionManager.getSession(any(), eq(TENANT_ID), any()))
                .thenReturn(new WorkflowBuilderSessionManager.SessionResult(null,
                        ToolExecutionResult.failure("no session")));

        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("id");
    }

    @Test
    @DisplayName("id via 'workflow_id' alias → resolved correctly")
    void workflowIdAlias_resolved() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "execute");
        p.put("workflow_id", WF_ID);   // alias, not "id"

        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isTrue();
        verify(workflowService).getWorkflow(UUID.fromString(WF_ID));
    }

    @Test
    @DisplayName("id from loaded session when no explicit id param")
    void idFromSession_fallback() {
        WorkflowBuilderSession session = mock(WorkflowBuilderSession.class);
        when(session.getLoadedWorkflowId()).thenReturn(WF_ID);
        when(sessionManager.getSession(any(), eq(TENANT_ID), any()))
                .thenReturn(new WorkflowBuilderSessionManager.SessionResult(session, null));

        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        Map<String, Object> p = Map.of("action", "execute");   // no id
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isTrue();
        verify(workflowService).getWorkflow(UUID.fromString(WF_ID));
    }

    @Test
    @DisplayName("Invalid UUID format → failure")
    void invalidUuid_failure() {
        ToolExecutionResult result = provider.execute("workflow",
                Map.of("action", "execute", "id", "not-a-valid-uuid"), CTX);

        assertThat(result.success()).isFalse();
    }

    // ── workflow validation ────────────────────────────────────────────────

    @Test
    @DisplayName("Workflow not found → failure")
    void workflowNotFound_failure() {
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.empty());

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    @DisplayName("Tenant mismatch → failure (treated as not found)")
    void tenantMismatch_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        entity.setTenantId("other-tenant");
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    @DisplayName("Workflow has no triggers → failure")
    void noTriggers_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Map<String, Object> emptyPlan = new LinkedHashMap<>();
        emptyPlan.put("triggers", List.of());
        emptyPlan.put("mcps", List.of());
        emptyPlan.put("cores", List.of());
        emptyPlan.put("edges", List.of());
        entity.setPlan(emptyPlan);
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("trigger");
    }

    // ── trigger resolution errors ──────────────────────────────────────────

    @Test
    @DisplayName("resolveTrigger throws → failure with the original message")
    void resolveTrigger_throws_returnsFailure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(agentWorkflowFireService.resolveTrigger(any(), eq("trigger:ghost")))
                .thenThrow(new IllegalArgumentException("Trigger not found: trigger:ghost. Available: trigger:start"));

        Map<String, Object> p = params();
        p.put("trigger_id", "trigger:ghost");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Trigger not found");
    }

    @Test
    @DisplayName("Non-fireable trigger with explicit trigger_id hint → resolveTrigger throws (skips bootstrap shortcut)")
    void nonFireableTrigger_withExplicitHint_failure() {
        // When the agent explicitly asks to fire a specific non-fireable trigger,
        // the bootstrap shortcut intentionally does NOT trigger - we want the
        // explicit "cannot fire X" error to surface so the agent sees the
        // mismatch instead of getting a misleading BOOTSTRAPPED success.
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.fromString(WF_ID));
        entity.setTenantId(TENANT_ID);
        Map<String, Object> wfTrigger = Map.of("id", "parent", "label", "Parent", "type", "workflow");
        entity.setPlan(Map.of("triggers", List.of(wfTrigger), "mcps", List.of(),
                "cores", List.of(), "edges", List.of()));
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(run);
        when(agentWorkflowFireService.resolveTrigger(any(), eq("trigger:parent")))
                .thenThrow(new IllegalArgumentException("Trigger 'trigger:parent' exists but is not agent-fireable"));

        Map<String, Object> p = params();
        p.put("trigger_id", "trigger:parent");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("fireable");
    }

    // ── bootstrap-only short-circuit (error/workflow-only plans) ───────────

    @Test
    @DisplayName("Error-only plan with no trigger_id → BOOTSTRAPPED success with seed run id")
    void bootstrapOnly_errorPlan_returnsBootstrapped() {
        // Plan has a single error trigger - no fire path exists, but createRun
        // still seeds a WAITING_TRIGGER run that the dispatcher reuses on
        // future parent failures. Provider must short-circuit BEFORE
        // resolveTrigger and return BOOTSTRAPPED.
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.fromString(WF_ID));
        entity.setTenantId(TENANT_ID);
        entity.setName("Error Handler");
        Map<String, Object> errTrigger = Map.of(
            "id", "a0162f59-fb0c-43e6-8266-7dc1519e964c",
            "label", "Catch", "type", "error");
        entity.setPlan(Map.of("triggers", List.of(errTrigger), "mcps", List.of(),
                "cores", List.of(), "edges", List.of()));

        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        WorkflowRunEntity seedRun = mock(WorkflowRunEntity.class);
        when(seedRun.getRunIdPublic()).thenReturn("run_seed_42");
        when(seedRun.getPlanVersion()).thenReturn(1);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(seedRun);
        when(agentWorkflowFireService.hasOnlyBootstrapTriggers(any())).thenReturn(true);

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        assertThat(data.get("status")).isEqualTo("BOOTSTRAPPED");
        assertThat(data.get("outcome")).isEqualTo("BOOTSTRAP_RUN_READY");
        assertThat(data.get("run_id")).isEqualTo("run_seed_42");
        assertThat(data.get("workflow_id")).isEqualTo(WF_ID);
        assertThat((String) data.get("message")).contains("Bootstrap WAITING_TRIGGER run is ready");

        // Critical: resolveTrigger and fire MUST NOT be called for bootstrap-only plans.
        verify(agentWorkflowFireService, never()).resolveTrigger(any(), any());
        verify(agentWorkflowFireService, never()).fire(any(), any(), any());
    }

    @Test
    @DisplayName("Workflow-only plan with no trigger_id → BOOTSTRAPPED success (same path as error)")
    void bootstrapOnly_workflowTriggerPlan_returnsBootstrapped() {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.fromString(WF_ID));
        entity.setTenantId(TENANT_ID);
        Map<String, Object> wfTrigger = Map.of(
            "id", "parent-uuid", "label", "OnParent", "type", "workflow");
        entity.setPlan(Map.of("triggers", List.of(wfTrigger), "mcps", List.of(),
                "cores", List.of(), "edges", List.of()));

        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        WorkflowRunEntity seedRun = mock(WorkflowRunEntity.class);
        when(seedRun.getRunIdPublic()).thenReturn("run_seed_wf");
        when(seedRun.getPlanVersion()).thenReturn(1);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(seedRun);
        when(agentWorkflowFireService.hasOnlyBootstrapTriggers(any())).thenReturn(true);

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        assertThat(data.get("status")).isEqualTo("BOOTSTRAPPED");
        verify(agentWorkflowFireService, never()).resolveTrigger(any(), any());
    }

    @Test
    @DisplayName("Bootstrap-only plan WITH explicit trigger_id → bypasses shortcut, lets resolveTrigger throw")
    void bootstrapOnly_withExplicitTriggerId_bypassesShortcut() {
        // The shortcut only kicks in when the agent has NOT named a specific
        // trigger. If the agent passes trigger_id='trigger:catch' on an
        // error-only plan, that's an explicit fire request - the agent should
        // see the "cannot fire" error rather than a silent BOOTSTRAPPED.
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.fromString(WF_ID));
        entity.setTenantId(TENANT_ID);
        Map<String, Object> errTrigger = Map.of(
            "id", "parent-uuid", "label", "Catch", "type", "error");
        entity.setPlan(Map.of("triggers", List.of(errTrigger), "mcps", List.of(),
                "cores", List.of(), "edges", List.of()));

        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(run);
        when(agentWorkflowFireService.resolveTrigger(any(), eq("trigger:catch")))
                .thenThrow(new IllegalArgumentException("Trigger 'trigger:catch' is not agent-fireable"));

        Map<String, Object> p = params();
        p.put("trigger_id", "trigger:catch");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not agent-fireable");
        // Shortcut must not be probed when the agent explicitly named a trigger.
        verify(agentWorkflowFireService, never()).hasOnlyBootstrapTriggers(any());
    }

    // ── successful execution ───────────────────────────────────────────────

    @Test
    @DisplayName("Successful execute - returns COMPLETED result with run_id")
    void successfulExecute_completedResult() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("run_id")).isEqualTo("run-1");
    }

    @Test
    @DisplayName("data_inputs forwarded to createRun and fire")
    void dataInputs_forwarded() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        Map<String, Object> dataInputs = Map.of("key", "value", "count", 42);
        stubSuccessfulExecution(entity, trigger, run, dataInputs);

        Map<String, Object> p = params();
        p.put("data_inputs", dataInputs);
        provider.execute("workflow", p, CTX);

        verify(agentWorkflowFireService).createRun(any(), any(), eq(dataInputs), eq(TENANT_ID));
        verify(agentWorkflowFireService).fire(run, trigger, dataInputs);
    }

    @Test
    @DisplayName("trigger_id hint forwarded to resolveTrigger")
    void triggerIdHint_forwarded() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(agentWorkflowFireService.resolveTrigger(any(), eq("trigger:start"))).thenReturn(trigger);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(run);
        TriggerExecutionResult tr = TriggerExecutionResult.success(
                "run-1", "trigger:start", TriggerType.MANUAL, Set.of(), 0);
        when(agentWorkflowFireService.fire(any(), any(), any())).thenReturn(tr);
        when(agentWorkflowFireService.buildResult(any(), any(), any(), any()))
                .thenReturn(Map.of("status", "COMPLETED"));
        lenient().when(run.getRunIdPublic()).thenReturn("run-1");

        Map<String, Object> p = params();
        p.put("trigger_id", "trigger:start");
        provider.execute("workflow", p, CTX);

        verify(agentWorkflowFireService).resolveTrigger(any(), eq("trigger:start"));
    }

    @Test
    @DisplayName("No data_inputs param → empty map passed to fire")
    void noDataInputs_emptyMapPassed() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        provider.execute("workflow", params(), CTX);   // no data_inputs param

        verify(agentWorkflowFireService).fire(run, trigger, Map.of());
    }

    // ── exception handling ─────────────────────────────────────────────────

    @Test
    @DisplayName("fire throws RuntimeException → failure wrapping the message")
    void fire_throws_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn("run-err");
        when(agentWorkflowFireService.resolveTrigger(any(), any())).thenReturn(trigger);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(run);
        when(agentWorkflowFireService.fire(any(), any(), any()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Redis connection refused");
    }

    @Test
    @DisplayName("createRun throws → failure")
    void createRun_throws_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID)))
                .thenThrow(new IllegalStateException("Run not found after creation: run-ghost"));

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Run not found after creation");
    }

    // ── version param (A+C feature) ────────────────────────────────────────
    // #FEATURE-PROD-VERSION-VISIBILITY: `version` param routes execute to different
    // selection modes - omitted = CURRENT (canvas editor run), integer = REPLAY_VERSION
    // (frozen historical version, editor run), literal "pinned" = PINNED (production fire
    // through ProductionRunResolver). Tests lock each branch and the input-validation
    // boundary.

    private com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity versionEntity(int v) {
        // Minimal plan that matches manualTriggerWorkflow()'s shape so resolveTrigger works.
        Map<String, Object> trigger = Map.of("id", "start", "label", "Start", "type", "manual");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(trigger));
        plan.put("mcps", List.of()); plan.put("cores", List.of()); plan.put("edges", List.of());
        var entity = new com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity(
                UUID.fromString(WF_ID), v, plan, TENANT_ID);
        return entity;
    }

    @Test
    @DisplayName("version=3 → replay path: loads version plan, calls createRunForVersion, skips createRun")
    void version_integer_replaysHistoricalVersion() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(planVersionService.getVersion(UUID.fromString(WF_ID), 3))
                .thenReturn(Optional.of(versionEntity(3)));

        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn("run-replay-3");
        when(agentWorkflowFireService.resolveTrigger(any(), any())).thenReturn(trigger);
        when(agentWorkflowFireService.createRunForVersion(any(), any(), eq(3), any(), eq(TENANT_ID)))
                .thenReturn(run);
        TriggerExecutionResult tr = TriggerExecutionResult.success(
                "run-replay-3", "trigger:start", TriggerType.MANUAL, Set.of(), 0);
        when(agentWorkflowFireService.fire(run, trigger, Map.of())).thenReturn(tr);
        when(agentWorkflowFireService.buildResult(eq(run), eq(tr), any(), any(), any()))
                .thenReturn(Map.of("status", "COMPLETED", "plan_version", 3));

        Map<String, Object> p = params();
        p.put("version", 3);
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isTrue();
        verify(planVersionService).getVersion(UUID.fromString(WF_ID), 3);
        verify(agentWorkflowFireService).createRunForVersion(any(), any(), eq(3), any(), eq(TENANT_ID));
        verify(agentWorkflowFireService, never()).createRun(any(), any(), any(), any());
    }

    @Test
    @DisplayName("version as numeric string '3' → same replay path as int 3")
    void version_numericString_replays() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(planVersionService.getVersion(UUID.fromString(WF_ID), 3))
                .thenReturn(Optional.of(versionEntity(3)));
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn("run-replay-3");
        when(agentWorkflowFireService.resolveTrigger(any(), any())).thenReturn(trigger);
        when(agentWorkflowFireService.createRunForVersion(any(), any(), eq(3), any(), eq(TENANT_ID))).thenReturn(run);
        when(agentWorkflowFireService.fire(any(), any(), any())).thenReturn(
                TriggerExecutionResult.success("run-replay-3", "trigger:start", TriggerType.MANUAL, Set.of(), 0));
        when(agentWorkflowFireService.buildResult(any(), any(), any(), any(), any())).thenReturn(Map.of());

        Map<String, Object> p = params();
        p.put("version", "3");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isTrue();
        verify(agentWorkflowFireService).createRunForVersion(any(), any(), eq(3), any(), eq(TENANT_ID));
    }

    @Test
    @DisplayName("version=99 (not found) → failure citing workflow id + version")
    void version_notFound_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(planVersionService.getVersion(UUID.fromString(WF_ID), 99)).thenReturn(Optional.empty());

        Map<String, Object> p = params();
        p.put("version", 99);
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("99").contains("not found");
        verify(agentWorkflowFireService, never()).createRun(any(), any(), any(), any());
        verify(agentWorkflowFireService, never()).createRunForVersion(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("version=0 → failure (must be positive)")
    void version_zero_rejected() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        Map<String, Object> p = params();
        p.put("version", 0);
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("positive");
    }

    @Test
    @DisplayName("version='bogus' → failure with usage hint")
    void version_invalidString_rejected() {
        WorkflowEntity entity = manualTriggerWorkflow();
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        Map<String, Object> p = params();
        p.put("version", "bogus");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid 'version' value").contains("pinned");
    }

    @Test
    @DisplayName("version='pinned' on unpinned workflow → failure explaining the pin requirement")
    void version_pinned_unpinned_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        entity.setPinnedVersion(null);
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));

        Map<String, Object> p = params();
        p.put("version", "pinned");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not pinned");
        verify(productionRunResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("version='pinned' but ProductionRunResolver returns NOT_PINNED → failure")
    void version_pinned_resolverNotPinned_failure() {
        WorkflowEntity entity = manualTriggerWorkflow();
        entity.setPinnedVersion(4);
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        when(productionRunResolver.resolve(UUID.fromString(WF_ID),
                        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED))
                .thenReturn(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                        Optional.empty(),
                        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NOT_PINNED,
                        "Test WF"));

        Map<String, Object> p = params();
        p.put("version", "pinned");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No production run").contains("NOT_PINNED");
    }

    @Test
    @DisplayName("version='pinned' happy path → fires prod run via ProductionRunResolver, no editor run created")
    void version_pinned_firesProductionRun() {
        WorkflowEntity entity = manualTriggerWorkflow();
        entity.setPinnedVersion(4);
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        WorkflowRunEntity prodRun = mock(WorkflowRunEntity.class);
        lenient().when(prodRun.getRunIdPublic()).thenReturn("run-prod-4");

        when(productionRunResolver.resolve(UUID.fromString(WF_ID),
                        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED))
                .thenReturn(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                        Optional.of(prodRun),
                        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                        "Test WF"));
        when(planVersionService.getVersion(UUID.fromString(WF_ID), 4))
                .thenReturn(Optional.of(versionEntity(4)));

        Trigger trigger = mock(Trigger.class);
        when(agentWorkflowFireService.resolveTrigger(any(), any())).thenReturn(trigger);
        TriggerExecutionResult tr = TriggerExecutionResult.success(
                "run-prod-4", "trigger:start", TriggerType.MANUAL, Set.of(), 0);
        when(agentWorkflowFireService.fire(prodRun, trigger, Map.of())).thenReturn(tr);
        when(agentWorkflowFireService.buildResult(eq(prodRun), eq(tr), any(), any(), any()))
                .thenReturn(Map.of("status", "COMPLETED", "plan_version", 4));

        Map<String, Object> p = params();
        p.put("version", "pinned");
        ToolExecutionResult result = provider.execute("workflow", p, CTX);

        assertThat(result.success()).isTrue();
        verify(agentWorkflowFireService).fire(prodRun, trigger, Map.of());
        // Critical: no editor run created - the prod run is reused directly.
        verify(agentWorkflowFireService, never()).createRun(any(), any(), any(), any());
        verify(agentWorkflowFireService, never()).createRunForVersion(any(), any(), anyInt(), any(), any());
    }

    // ── early visualization publish ──────────────────────────────────────

    @Test
    @DisplayName("Successful execute publishes visualization_ready before fire")
    void successfulExecute_publishesVisualizationReadyBeforeFire() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        provider.execute("workflow", params(), CTX);

        var inOrder = inOrder(conversationEventPublisher, agentWorkflowFireService);
        inOrder.verify(conversationEventPublisher).publishVisualizationReady(
                any(), any(), eq("workflow_run"), eq(WF_ID), eq("Execute Test Workflow"), eq("run-1"));
        inOrder.verify(agentWorkflowFireService).fire(any(), any(), any());
    }

    @Test
    @DisplayName("Bootstrap path also publishes visualization_ready")
    void bootstrapPath_publishesVisualizationReady() {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.fromString(WF_ID));
        entity.setTenantId(TENANT_ID);
        entity.setName("Error Handler");
        Map<String, Object> errTrigger = Map.of("id", "err-uuid", "label", "Catch", "type", "error");
        entity.setPlan(Map.of("triggers", List.of(errTrigger), "mcps", List.of(),
                "cores", List.of(), "edges", List.of()));

        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.of(entity));
        WorkflowRunEntity seedRun = mock(WorkflowRunEntity.class);
        when(seedRun.getRunIdPublic()).thenReturn("run_seed_viz");
        when(seedRun.getPlanVersion()).thenReturn(1);
        when(agentWorkflowFireService.createRun(any(), any(), any(), eq(TENANT_ID))).thenReturn(seedRun);
        when(agentWorkflowFireService.hasOnlyBootstrapTriggers(any())).thenReturn(true);

        provider.execute("workflow", params(), CTX);

        verify(conversationEventPublisher).publishVisualizationReady(
                any(), any(), eq("workflow_run"), eq(WF_ID), eq("Error Handler"), eq("run_seed_viz"));
    }

    @Test
    @DisplayName("Workflow not found → publisher never called")
    void workflowNotFound_publisherNotCalled() {
        when(workflowService.getWorkflow(UUID.fromString(WF_ID))).thenReturn(Optional.empty());

        provider.execute("workflow", params(), CTX);

        verify(conversationEventPublisher, never()).publishVisualizationReady(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("version param absent → original CURRENT behavior preserved (createRun)")
    void version_absent_preservesCurrentBehavior() {
        WorkflowEntity entity = manualTriggerWorkflow();
        Trigger trigger = mock(Trigger.class);
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        stubSuccessfulExecution(entity, trigger, run, Map.of());

        ToolExecutionResult result = provider.execute("workflow", params(), CTX);

        assertThat(result.success()).isTrue();
        verify(agentWorkflowFireService).createRun(any(), any(), any(), eq(TENANT_ID));
        verify(agentWorkflowFireService, never()).createRunForVersion(any(), any(), anyInt(), any(), any());
        verify(productionRunResolver, never()).resolve(any(), any());
    }

    // ── resolve_approval / continue_interface (advance a paused run) ────────

    private static final String RUN_ID = "run-paused-1";

    /** A run owned by the caller's tenant → passes the in-scope guard. */
    private WorkflowRunEntity inScopeRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(TENANT_ID);
        return run;
    }

    private com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity pendingSig(String nodeId) {
        var s = mock(com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.class);
        lenient().when(s.getNodeId()).thenReturn(nodeId);
        return s;
    }

    private Map<String, Object> sigParams(String action, String... extras) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("run_id", RUN_ID);
        for (int i = 0; i + 1 < extras.length; i += 2) p.put(extras[i], extras[i + 1]);
        return p;
    }

    @Test
    @DisplayName("resolve_approval without run_id → failure mentioning run_id")
    void resolveApproval_missingRunId() {
        Map<String, Object> p = Map.of("action", "resolve_approval", "decision", "approved");
        ToolExecutionResult r = provider.execute("workflow", p, CTX);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("run_id");
    }

    @Test
    @DisplayName("resolve_approval on a run not in the caller's scope → not found (no probing)")
    void resolveApproval_runNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());
        ToolExecutionResult r = provider.execute("workflow", sigParams("resolve_approval", "decision", "approved"), CTX);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not found");
        verifyNoInteractions(runSignalResolution);
    }

    @Test
    @DisplayName("resolve_approval with an invalid decision → must be approved or rejected")
    void resolveApproval_invalidDecision() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScopeRun()));
        ToolExecutionResult r = provider.execute("workflow", sigParams("resolve_approval", "decision", "maybe"), CTX);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("approved").contains("rejected");
    }

    @Test
    @DisplayName("resolve_approval approved + single pending → auto node_id, delegates APPROVED, success")
    void resolveApproval_approvedSinglePending() {
        var sig = pendingSig("core:review");
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScopeRun()));
        when(runSignalResolution.pendingOfType(RUN_ID,
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL))
                .thenReturn(List.of(sig));
        when(runSignalResolution.resolveApproval(eq(RUN_ID), eq("core:review"),
                eq(com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED),
                any(), eq(TENANT_ID), any(), any()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService.Outcome(
                        true, "resolved", 7L, 1, "APPROVED"));

        ToolExecutionResult r = provider.execute("workflow", sigParams("resolve_approval", "decision", "approved"), CTX);

        assertThat(r.success()).isTrue();
        verify(runSignalResolution).resolveApproval(eq(RUN_ID), eq("core:review"),
                eq(com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED),
                any(), eq(TENANT_ID), any(), any());
    }

    @Test
    @DisplayName("resolve_approval with multiple pending and no node_id → asks for node_id")
    void resolveApproval_multiplePending_asksForNodeId() {
        var s1 = pendingSig("core:a");
        var s2 = pendingSig("core:b");
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScopeRun()));
        when(runSignalResolution.pendingOfType(RUN_ID,
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL))
                .thenReturn(List.of(s1, s2));

        ToolExecutionResult r = provider.execute("workflow", sigParams("resolve_approval", "decision", "approved"), CTX);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("node_id").contains("core:a");
        verify(runSignalResolution, never()).resolveApproval(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("continue_interface + single pending → delegates and succeeds")
    void continueInterface_singlePending() {
        var sig = pendingSig("interface:page");
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScopeRun()));
        when(runSignalResolution.pendingOfType(RUN_ID,
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL))
                .thenReturn(List.of(sig));
        when(runSignalResolution.continueInterface(eq(RUN_ID), eq("interface:page"), any(), eq(TENANT_ID), any(), any()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService.Outcome(
                        true, "resolved", 9L, 1, "CONTINUE"));

        ToolExecutionResult r = provider.execute("workflow", sigParams("continue_interface"), CTX);

        assertThat(r.success()).isTrue();
        verify(runSignalResolution).continueInterface(eq(RUN_ID), eq("interface:page"), any(), eq(TENANT_ID), any(), any());
    }

    @Test
    @DisplayName("continue_interface with no pending interface → not-found message")
    void continueInterface_noPending() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScopeRun()));
        when(runSignalResolution.pendingOfType(RUN_ID,
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL))
                .thenReturn(List.of());

        ToolExecutionResult r = provider.execute("workflow", sigParams("continue_interface"), CTX);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("No pending interface");
    }
}
