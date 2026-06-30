package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the per-resource access-mode (read/write) gate added to
 * {@link WorkflowBuilderProvider#execute} on the BUILDER path.
 *
 * <p>A read-mode agent ({@code workflowAccessMode='read'}) may INSPECT a workflow
 * (load / describe / get_plan / get_node_output / validate / runs / …) but must NOT
 * mutate one (add_node / connect / finish / save / set_plan / …). A write-mode or
 * absent-mode agent is allowed everything (default = full access). The CRUD-delegated
 * actions are also gated inside {@code WorkflowCrudModule}; this top-level gate is the
 * one place the builder-specific mutation actions are enforced.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - access-mode (read/write) gate")
class WorkflowBuilderProviderAccessModeTest {

    @Mock private WorkflowBuilderSessionManager sessionManager;
    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowDraftAutoSaver draftAutoSaver;
    @Mock private WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock private WorkflowBuilderLogger buildLogger;
    @Mock private com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule crudModule;

    @Mock private WorkflowManagementService workflowService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private NodeParamsValidator nodeParamsValidator;
    @Mock private WorkflowHelpProvider workflowHelpProvider;

    @Mock private WorkflowBuilderCreator creator;
    @Mock private WorkflowBuilderConnectionManager connectionManager;
    @Mock private WorkflowBuilderModifier modifier;
    @Mock private WorkflowBuilderViewer viewer;
    @Mock private WorkflowBuilderLoader loader;
    @Mock private WorkflowBuilderTableOperations tableOperations;
    @Mock private WorkflowBuilderPlanExporter planExporter;
    @Mock private WorkflowBuilderHelpModule helpModule;

    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private AgentWorkflowFireService agentWorkflowFireService;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService runSignalResolution;
    @Mock private com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;

    @Spy private AgentDefaultsConfig agentDefaults = new AgentDefaultsConfig();

    @InjectMocks
    private WorkflowBuilderProvider provider;

    private static final String TENANT = "tenant-1";

    /** Context carrying a per-resource workflow access mode in credentials (null = absent). */
    private ToolExecutionContext ctxWithMode(String workflowAccessMode) {
        Map<String, Object> creds = new LinkedHashMap<>();
        if (workflowAccessMode != null) {
            creds.put("__workflowAccessMode__", workflowAccessMode);
        }
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, null, null);
    }

    private Map<String, Object> params(String action) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        return p;
    }

    /** Context carrying a restricted workflow allow-list (allowedWorkflowIds) in credentials. */
    private ToolExecutionContext ctxWithAllowedWorkflows(String... ids) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("allowedWorkflowIds", java.util.List.of(ids));
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, null, null);
    }

    // ── allow-list (allowedWorkflowIds) gates builder plan-reads ────────────────

    /** Read actions flow through execute()'s tail enricher (addSessionSnapshot) - pass the result through. */
    private void passThroughEnricher() {
        org.mockito.Mockito.when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("allow-list: load of a workflow_id NOT in allowedWorkflowIds is denied before the loader (plan-read leak)")
    void allowListDeniesLoadOutOfList() {
        passThroughEnricher();
        Map<String, Object> p = params("load");
        p.put("workflow_id", "wf-B");

        ToolExecutionResult result = provider.execute("workflow", p, ctxWithAllowedWorkflows("wf-A"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("approved workflow list");
        verify(loader, never()).executeLoad(any(), any(), any(), any());
    }

    @Test
    @DisplayName("allow-list: get_plan of a workflow id NOT in allowedWorkflowIds is denied before the session resolves")
    void allowListDeniesGetPlanOutOfList() {
        passThroughEnricher();
        Map<String, Object> p = params("get_plan");
        p.put("id", "wf-B");

        ToolExecutionResult result = provider.execute("workflow", p, ctxWithAllowedWorkflows("wf-A"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verify(sessionManager, never()).getSession(any(), anyString(), any());
    }

    @Test
    @DisplayName("allow-list: load of an IN-list workflow id passes the gate (reaches the loader)")
    void allowListAllowsInListLoad() {
        passThroughEnricher();
        Map<String, Object> p = params("load");
        p.put("workflow_id", "wf-A");
        when(loader.executeLoad(any(), any(), any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of()));

        ToolExecutionResult result = provider.execute("workflow", p, ctxWithAllowedWorkflows("wf-A"));

        assertThat(result.success()).isTrue();
        verify(loader).executeLoad(any(), any(), any(), any());
    }

    // ── read-mode DENIES write actions ─────────────────────────────────────────

    @Test
    @DisplayName("read-mode agent is DENIED add_node (PERMISSION_DENIED, never reaches node creation)")
    void readModeDeniesAddNode() {
        Map<String, Object> p = params("add_node");
        p.put("type", "decision");
        p.put("label", "Check thing");

        ToolExecutionResult result = provider.execute("workflow", p, ctxWithMode("read"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("read-only").contains("add_node");
        // The gate fires before any node-creation work - the session is never resolved
        // and no creator dispatcher (executeAddDecision/…) is ever invoked.
        verify(sessionManager, never()).getSession(any(), anyString(), any());
        verify(creator, never()).executeAddDecision(any(), any());
    }

    @Test
    @DisplayName("read-mode agent is DENIED finish (create alias resolves to the same write action)")
    void readModeDeniesFinish() {
        ToolExecutionResult result = provider.execute("workflow", params("finish"), ctxWithMode("read"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("read-only").contains("finish");
    }

    @Test
    @DisplayName("read-mode agent is DENIED the 'create' alias too (finish's twin write action)")
    void readModeDeniesCreateAlias() {
        ToolExecutionResult result = provider.execute("workflow", params("create"), ctxWithMode("read"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        // 'create' is a hidden action (not aliased to 'finish'), so the message names 'create';
        // either way it is NOT a READ action and is correctly blocked in read-mode.
        assertThat(result.error()).contains("read-only").contains("create");
    }

    // ── read-mode ALLOWS read actions ──────────────────────────────────────────

    @Test
    @DisplayName("read-mode agent is ALLOWED describe (inspection passes the gate, reaches the viewer)")
    void readModeAllowsDescribe() {
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(viewer.executeGetSummary(any())).thenReturn(ToolExecutionResult.success(Map.of("nodes", java.util.List.of())));
        when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        ToolExecutionResult result = provider.execute("workflow", params("describe"), ctxWithMode("read"));

        assertThat(result.success()).isTrue();
        verify(viewer).executeGetSummary(any());
    }

    @Test
    @DisplayName("read-mode agent is ALLOWED get_node_output (newly-added builder read action, delegated to CRUD)")
    void readModeAllowsGetNodeOutput() {
        // get_node_output delegates to the CRUD module, which ALSO checks access mode - but
        // since it is a READ action, both gates short-circuit and the module is reached.
        when(crudModule.execute(org.mockito.ArgumentMatchers.eq("get_node_output"), any(), anyString(), any()))
                .thenReturn(java.util.Optional.of(ToolExecutionResult.success(Map.of("output", Map.of()))));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> p = params("get_node_output");
        p.put("run_id", "run-1");
        p.put("node_id", "node-1");
        ToolExecutionResult result = provider.execute("workflow", p, ctxWithMode("read"));

        assertThat(result.success()).isTrue();
        verify(crudModule).execute(org.mockito.ArgumentMatchers.eq("get_node_output"), any(), anyString(), any());
    }

    @Test
    @DisplayName("read-mode agent is ALLOWED read_rows (builder-internal TABLE read, reaches tableOperations)")
    void readModeAllowsReadRows() {
        // read_rows is a pure read dispatched through the builder (delegateTableOperation),
        // so it must pass the workflow access-mode gate in read-mode and reach the executor.
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(tableOperations.execute(any(), any(), anyString(), org.mockito.ArgumentMatchers.eq("read_rows")))
                .thenReturn(ToolExecutionResult.success(Map.of("rows", java.util.List.of())));
        when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        ToolExecutionResult result = provider.execute("workflow", params("read_rows"), ctxWithMode("read"));

        assertThat(result.success()).isTrue();
        verify(tableOperations).execute(any(), any(), anyString(), org.mockito.ArgumentMatchers.eq("read_rows"));
    }

    @Test
    @DisplayName("read-mode agent is ALLOWED find_rows (the 'query_rows'/'find' aliases canonicalize to it before the gate)")
    void readModeAllowsFindRows() {
        // 'query_rows' is an alias of 'find_rows' (resolveAlias) - the canonical action the gate
        // sees is 'find_rows', which is now a workflow READ action, so read-mode lets it through.
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(tableOperations.execute(any(), any(), anyString(), org.mockito.ArgumentMatchers.eq("find_rows")))
                .thenReturn(ToolExecutionResult.success(Map.of("rows", java.util.List.of())));
        when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        ToolExecutionResult result = provider.execute("workflow", params("query_rows"), ctxWithMode("read"));

        assertThat(result.success()).isTrue();
        verify(tableOperations).execute(any(), any(), anyString(), org.mockito.ArgumentMatchers.eq("find_rows"));
    }

    @Test
    @DisplayName("read-mode agent is DENIED insert_row (table row WRITE stays gated, contrast read_rows)")
    void readModeDeniesInsertRow() {
        ToolExecutionResult result = provider.execute("workflow", params("insert_row"), ctxWithMode("read"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("read-only").contains("insert_row");
        // The gate fires before the table operation - the executor is never reached.
        verify(tableOperations, never()).execute(any(), any(), anyString(), anyString());
    }

    // ── write / absent mode ALLOWS write actions ───────────────────────────────
    // set_plan is the cleanest write action to assert "the gate let it through": it
    // delegates straight to planExporter.executeSetPlan with no node-validation pipeline.

    @Test
    @DisplayName("write-mode agent is ALLOWED set_plan (passes the gate, reaches the plan importer)")
    void writeModeAllowsSetPlan() {
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        // set_plan is in PLAN_MUTATING_ACTIONS → the APPLICATION-immutability guard runs first;
        // an empty session lookup makes it a pass-through (no loaded application).
        when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        when(sessionStore.getSessionForConversation(anyString(), any())).thenReturn(java.util.Optional.empty());
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(planExporter.executeSetPlan(any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "OK")));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> p = params("set_plan");
        p.put("plan", Map.of("triggers", java.util.List.of(), "mcps", java.util.List.of()));
        ToolExecutionResult result = provider.execute("workflow", p, ctxWithMode("write"));

        assertThat(result.success()).isTrue();
        verify(planExporter).executeSetPlan(any(), any());
    }

    @Test
    @DisplayName("absent access mode is ALLOWED set_plan (default = full access, no regression for existing agents)")
    void absentModeAllowsSetPlan() {
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        // set_plan is in PLAN_MUTATING_ACTIONS → the APPLICATION-immutability guard runs first;
        // an empty session lookup makes it a pass-through (no loaded application).
        when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        when(sessionStore.getSessionForConversation(anyString(), any())).thenReturn(java.util.Optional.empty());
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(planExporter.executeSetPlan(any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "OK")));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> p = params("set_plan");
        p.put("plan", Map.of("triggers", java.util.List.of(), "mcps", java.util.List.of()));
        // ctxWithMode(null) → no __workflowAccessMode__ credential at all.
        ToolExecutionResult result = provider.execute("workflow", p, ctxWithMode(null));

        assertThat(result.success()).isTrue();
        verify(planExporter).executeSetPlan(any(), any());
    }
}
