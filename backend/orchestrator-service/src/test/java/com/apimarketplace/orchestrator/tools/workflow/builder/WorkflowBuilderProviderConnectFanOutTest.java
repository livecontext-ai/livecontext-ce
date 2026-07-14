package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end-through-the-MCP-tool-entry test of the "one output port = one target"
 * rule. Unlike {@link WorkflowBuilderConnectionManagerPortFanOutTest} (which calls
 * the manager directly) and {@link WorkflowBuilderProviderExecuteTest} (which MOCKS
 * the manager), this drives the real {@link WorkflowBuilderProvider#execute} MCP
 * entry point - action routing → session resolution → REAL
 * {@link WorkflowBuilderConnectionManager#executeConnect} → result enrichment - the
 * exact path an agent's {@code workflow(action='connect', ...)} tool call takes.
 *
 * The per-node-type exhaustiveness lives in the manager test; here we prove the
 * dispatch wiring actually delivers the rejection + Fork hint to the agent, and
 * that the port-less implicit-fork exemption survives the full dispatch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - connect dispatch enforces one port = one target")
class WorkflowBuilderProviderConnectFanOutTest {

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
    @Mock WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderProvider provider;

    private static final String TENANT_ID = "tenant-connect-fanout";
    private static final ToolExecutionContext CTX = ToolExecutionContext.of(TENANT_ID);

    @BeforeEach
    void setUp() {
        // REAL connection manager - the code under test through the dispatch.
        WorkflowBuilderConnectionManager connectionManager = new WorkflowBuilderConnectionManager(sessionStore);

        provider = new WorkflowBuilderProvider(
            sessionManager, resultEnricher, draftAutoSaver, toolDefinitionFactory, buildLogger,
            crudModule, workflowService, interfaceClient, nodeTypeSearchService, nodeLibraryService,
            nodeParamsValidator, workflowHelpProvider, creator, connectionManager, modifier, viewer,
            loader, tableOperations, planExporter, helpModule, executionService, workflowRunRepository,
            agentWorkflowFireService, runSignalResolution, planVersionService, productionRunResolver,
            new com.apimarketplace.orchestrator.config.AgentDefaultsConfig(),
            conversationEventPublisher, null /* mockOutputSuggester - not exercised here */
        );
        // enrichResult / addSessionSnapshot pass through so the test sees the real connect result.
        lenient().when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Returns the same real session for every getSession call, so edges accumulate across calls. */
    private void useSession(WorkflowBuilderSession session) {
        lenient().when(sessionManager.getSession(any(), eq(TENANT_ID), any()))
                .thenReturn(new WorkflowBuilderSessionManager.SessionResult(session, null));
    }

    private WorkflowBuilderSession sessionWith() {
        WorkflowBuilderSession s = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId(TENANT_ID).workflowName("W")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        addMcp(s, "Step A");
        addMcp(s, "Step B");
        return s;
    }

    private void addMcp(WorkflowBuilderSession s, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "mcp:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", "mcp");
        n.put("label", label);
        s.getMcps().add(n);
    }

    private void addCore(WorkflowBuilderSession s, String type, String label, String cfgKey, List<Map<String, Object>> cfg) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "core:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", type);
        n.put("label", label);
        if (cfgKey != null) n.put(cfgKey, cfg);
        s.getCores().add(n);
    }

    private void addTrigger(WorkflowBuilderSession s, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", "manual");
        n.put("label", label);
        s.getTriggers().add(n);
    }

    private static List<Map<String, Object>> conds(String... types) {
        return java.util.Arrays.stream(types)
                .map(t -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", t); return (Map<String, Object>) m; })
                .toList();
    }

    private static List<Map<String, Object>> outs(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> (Map<String, Object>) new LinkedHashMap<String, Object>()).toList();
    }

    private ToolExecutionResult connect(String from, String to) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "connect");
        p.put("from", from);
        p.put("to", to);
        return provider.execute("workflow", p, CTX);
    }

    @Test
    @DisplayName("agent connect via MCP: 2nd edge from a decision port is rejected with the Fork hint")
    void decisionPortRejectedThroughDispatch() {
        WorkflowBuilderSession s = sessionWith();
        addCore(s, "decision", "Check", "decisionConditions", conds("if", "else"));
        useSession(s);

        assertThat(connect("Check:if", "Step A").success()).isTrue();

        ToolExecutionResult second = connect("Check:if", "Step B");
        assertThat(second.success()).isFalse();
        assertThat(second.error()).containsIgnoringCase("port").containsIgnoringCase("fork");
    }

    @Test
    @DisplayName("agent connect via MCP: 2nd edge from a fork branch port is rejected")
    void forkBranchRejectedThroughDispatch() {
        WorkflowBuilderSession s = sessionWith();
        addCore(s, "fork", "Parallel", "forkOutputs", outs(2));
        useSession(s);

        assertThat(connect("Parallel:branch_0", "Step A").success()).isTrue();
        assertThat(connect("Parallel:branch_0", "Step B").success()).isFalse();
    }

    @Test
    @DisplayName("agent connect via MCP: distinct ports (if then else) both succeed")
    void distinctPortsSucceedThroughDispatch() {
        WorkflowBuilderSession s = sessionWith();
        addCore(s, "decision", "Check", "decisionConditions", conds("if", "else"));
        useSession(s);

        assertThat(connect("Check:if", "Step A").success()).isTrue();
        assertThat(connect("Check:else", "Step B").success()).isTrue();
    }

    @Test
    @DisplayName("agent connect via MCP: port-less trigger keeps implicit fork (both edges succeed)")
    void triggerImplicitForkSucceedsThroughDispatch() {
        WorkflowBuilderSession s = sessionWith();
        addTrigger(s, "Start");
        useSession(s);

        assertThat(connect("Start", "Step A").success()).isTrue();
        assertThat(connect("Start", "Step B").success()).isTrue();
    }
}
