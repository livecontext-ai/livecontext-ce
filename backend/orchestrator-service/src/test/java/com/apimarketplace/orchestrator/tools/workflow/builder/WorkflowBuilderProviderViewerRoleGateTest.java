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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the MCP half of the VIEWER gap: the workflow tool gated the per-AGENT
 * access mode but never the per-PERSON workspace role, so a VIEWER driving an agent could
 * delete, pin, execute, stop or rewrite org workflows the REST surface refuses them
 * (the MCP delete even called the 2-arg {@code deleteWorkflow}, so the guard never saw the
 * role). A VIEWER now gets exactly the READ actions a read-mode agent gets.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - workspace VIEWER role is read-only on the workflow tool")
class WorkflowBuilderProviderViewerRoleGateTest {

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

    private static final String TENANT = "tenant-v";
    private static final String ORG = "org-v";

    private static ToolExecutionContext ctx(String orgId, String orgRole) {
        return new ToolExecutionContext(TENANT, new LinkedHashMap<>(), Map.of(), Set.of(),
                null, null, orgId, orgRole);
    }

    private static Map<String, Object> params(String action) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("workflow_id", "4f1c7a1e-8a6b-4b3e-9d2a-1c2b3d4e5f60");
        return p;
    }

    @ParameterizedTest(name = "VIEWER is denied workflow(action=''{0}'')")
    @ValueSource(strings = {"delete", "pin", "unpin", "stop_run", "restart_from_node", "publish",
            "execute", "set_plan", "save", "add_node"})
    @DisplayName("a VIEWER is denied every write action, before any module is reached")
    void viewerDeniedWrites(String action) {
        ToolExecutionResult result = provider.execute("workflow", params(action), ctx(ORG, "VIEWER"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("VIEWER").contains(action);
        verifyNoInteractions(crudModule, planExporter, creator, loader, executionService, agentWorkflowFireService);
    }

    @Test
    @DisplayName("a VIEWER keeps the READ actions (get_node_output reaches the CRUD module)")
    void viewerKeepsReads() {
        when(crudModule.execute(eq("get_node_output"), any(), anyString(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("output", Map.of()))));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> p = params("get_node_output");
        p.put("run_id", "run-1");
        p.put("node_id", "node-1");

        ToolExecutionResult result = provider.execute("workflow", p, ctx(ORG, "VIEWER"));

        assertThat(result.success()).isTrue();
        verify(crudModule).execute(eq("get_node_output"), any(), anyString(), any());
    }

    @Test
    @DisplayName("a MEMBER still writes: set_plan passes the role gate and reaches the importer")
    void memberStillWrites() {
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(new WorkflowBuilderSession(), null);
        when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        when(sessionStore.getSessionForConversation(anyString(), any())).thenReturn(Optional.empty());
        when(sessionManager.getSession(any(), anyString(), any())).thenReturn(sessionResult);
        when(planExporter.executeSetPlan(any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of("status", "OK")));
        when(resultEnricher.addSessionSnapshot(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> p = params("set_plan");
        p.put("plan", Map.of("triggers", List.of(), "mcps", List.of()));

        ToolExecutionResult result = provider.execute("workflow", p, ctx(ORG, "MEMBER"));

        assertThat(result.success()).isTrue();
        verify(planExporter).executeSetPlan(any(), any());
    }
}
