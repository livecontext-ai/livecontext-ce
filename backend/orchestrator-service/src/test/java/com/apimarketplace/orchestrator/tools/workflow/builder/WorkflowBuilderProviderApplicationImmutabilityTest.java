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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the dispatch-level APPLICATION-immutability guard in
 * {@link WorkflowBuilderProvider#execute} added 2026-05-15.
 *
 * <p>Without this guard, an agent that loads an APPLICATION workflow via
 * {@code workflow(action='load', id='<app-uuid>')} could subsequently call
 * {@code workflow(action='modify')}, {@code add_node}, {@code connect},
 * {@code set_plan}, etc., and silently drift the acquired plan in place. The
 * acquired plan is the contract the marketplace acquirer received - only
 * {@code POST /workflows/{id}/reset-plan} restores from {@code basePlan}.
 *
 * <p>The guard reads {@code loadedWorkflowIsApplication} from the session
 * (pinned at load time by {@code WorkflowBuilderLoader.convertWorkflowToSession})
 * so dispatch is O(1) memory - no DB round-trip per modifying action.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - APPLICATION dispatch guard")
class WorkflowBuilderProviderApplicationImmutabilityTest {

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

    @Spy private AgentDefaultsConfig agentDefaults = new AgentDefaultsConfig();

    @InjectMocks
    private WorkflowBuilderProvider provider;

    private ToolExecutionContext ctx;
    private static final String TENANT = "tenant-1";
    private static final UUID APP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ctx = ToolExecutionContext.of(TENANT);
        // Lenient: the guard only queries sessionStore for MODIFYING_ACTIONS, so the
        // non-modifying `load` test legitimately doesn't reach this call.
        lenient().when(sessionManager.getSessionStore()).thenReturn(sessionStore);
    }

    private WorkflowBuilderSession sessionWithApplicationLoaded() {
        WorkflowBuilderSession s = new WorkflowBuilderSession();
        s.setTenantId(TENANT);
        s.setSessionId("sess-1");
        s.setLoadedWorkflowId(APP_ID.toString());
        s.setLoadedWorkflowIsApplication(true);
        return s;
    }

    private WorkflowBuilderSession sessionWithRegularLoaded() {
        WorkflowBuilderSession s = new WorkflowBuilderSession();
        s.setTenantId(TENANT);
        s.setSessionId("sess-2");
        s.setLoadedWorkflowId(APP_ID.toString());
        s.setLoadedWorkflowIsApplication(false);
        return s;
    }

    private Map<String, Object> params(String action) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        return p;
    }

    @Test
    @DisplayName("workflow(action='modify') on a loaded APPLICATION returns RESOURCE_CONFLICT and never reaches the modifier")
    void modifyOnApplicationRejected() {
        when(sessionStore.getSessionForConversation(eq(TENANT), any()))
                .thenReturn(Optional.of(sessionWithApplicationLoaded()));

        ToolExecutionResult result = provider.execute("workflow", params("modify"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(result.error())
                .contains("APPLICATION")
                .contains("workflow(action='discard')")
                .doesNotContain("POST /")
                .doesNotContain("reset-plan");
        assertThat(result.metadata())
                .containsEntry("code", "APPLICATION_PLAN_IMMUTABLE")
                .containsEntry("workflow_id", APP_ID.toString())
                .containsEntry("action", "modify");

        verify(modifier, never()).executeModifyNode(any(), any());
        verify(draftAutoSaver, never()).autoSaveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("workflow(action='add_node') on a loaded APPLICATION is rejected by the same dispatch guard")
    void addNodeOnApplicationRejected() {
        when(sessionStore.getSessionForConversation(eq(TENANT), any()))
                .thenReturn(Optional.of(sessionWithApplicationLoaded()));

        Map<String, Object> p = params("add_node");
        p.put("type", "decision");
        p.put("label", "Check thing");
        ToolExecutionResult result = provider.execute("workflow", p, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(result.metadata()).containsEntry("action", "add_node");
        // Add-node never reached: creator (executeAddTrigger / executeAddNode dispatchers) untouched.
        verify(creator, never()).executeAddTrigger(any(), any(), any());
    }

    @Test
    @DisplayName("workflow(action='connect') on a loaded APPLICATION is rejected (mutating edges = mutating plan)")
    void connectOnApplicationRejected() {
        when(sessionStore.getSessionForConversation(eq(TENANT), any()))
                .thenReturn(Optional.of(sessionWithApplicationLoaded()));

        Map<String, Object> p = params("connect");
        p.put("from", "trigger:webhook");
        p.put("to", "agent:bot");
        ToolExecutionResult result = provider.execute("workflow", p, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        verify(connectionManager, never()).executeConnect(any(), any());
    }

    @Test
    @DisplayName("workflow(action='set_plan') on a loaded APPLICATION is rejected - the most surgical full-plan overwrite is gated")
    void setPlanOnApplicationRejected() {
        when(sessionStore.getSessionForConversation(eq(TENANT), any()))
                .thenReturn(Optional.of(sessionWithApplicationLoaded()));

        Map<String, Object> p = params("set_plan");
        p.put("plan", Map.of("triggers", java.util.List.of(), "mcps", java.util.List.of()));
        ToolExecutionResult result = provider.execute("workflow", p, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(result.metadata())
                .containsEntry("code", "APPLICATION_PLAN_IMMUTABLE")
                .containsEntry("action", "set_plan");
        // The plan-import path is never invoked.
        verify(planExporter, never()).executeSetPlan(any(), any());
    }

    @Test
    @DisplayName("workflow(action='read_rows') on a loaded APPLICATION is NOT rejected - pure read, excluded from PLAN_MUTATING_ACTIONS")
    void readRowsOnApplicationNotBlocked() {
        // read_rows is in MODIFYING_ACTIONS (auto-save trigger set) but NOT in
        // PLAN_MUTATING_ACTIONS (the immutability gate). Without the distinct set
        // introduced in this PR, an acquirer would be wrongly refused row inspection
        // on their acquired app's table - a UX regression. This test pins the
        // boundary: the gate must NOT fire on pure reads.
        // sessionStore stub left unstubbed: read_rows is not in PLAN_MUTATING_ACTIONS,
        // so the guard's session lookup is skipped - verifies the gate scope is correct.
        // Stub the session lookup that delegateTableOperation reaches (post-guard).
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(sessionWithApplicationLoaded(), null);
        when(sessionManager.getSession(any(), eq(TENANT), any())).thenReturn(sessionResult);
        when(tableOperations.execute(any(), any(), eq(TENANT), org.mockito.ArgumentMatchers.eq("read_rows")))
                .thenReturn(ToolExecutionResult.success(Map.of("rows", java.util.List.of())));
        when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> p = params("read_rows");
        p.put("table_id", "tbl-1");
        ToolExecutionResult result = provider.execute("workflow", p, ctx);

        assertThat(result.success()).isTrue();
        verify(tableOperations).execute(any(), any(), eq(TENANT), org.mockito.ArgumentMatchers.eq("read_rows"));
    }

    @Test
    @DisplayName("workflow(action='load') is NOT a modifying action - guard does not interfere with reading an APPLICATION")
    void loadOnApplicationNotBlocked() {
        // No stubbing of sessionStore needed - `load` is not in MODIFYING_ACTIONS, so the
        // guard's session lookup is skipped entirely. Verifies the guard scope.
        when(loader.executeLoad(eq(TENANT), any(), any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "OK", "loaded", true)));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> p = params("load");
        p.put("id", APP_ID.toString());
        ToolExecutionResult result = provider.execute("workflow", p, ctx);

        assertThat(result.success()).isTrue();
        verify(loader).executeLoad(eq(TENANT), any(), any(), any());
    }
}
