package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.service.validation.ValidationError;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionManager.SessionResult;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Unit tests for {@link WorkflowBuilderProvider}, the {@code workflow} tool dispatcher
 * (~1300 LOC). This provider is a thin-but-wide router: it validates the action, enforces
 * the per-resource access mode + APPLICATION-immutability + allow-list gates, then
 * delegates to ~14 specialized handlers. These tests pin the FRAMING and ROUTING logic
 * (the part that lives in this class) - the handlers themselves are mocked and covered by
 * their own suites.
 *
 * <p>Strategy: every collaborator is a Mockito mock. {@code resultEnricher.enrichResult}
 * and {@code resultEnricher.addSessionSnapshot} (called on the success path of {@code execute})
 * are stubbed to echo their first argument, so assertions see the raw dispatch outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider (workflow tool dispatcher)")
class WorkflowBuilderProviderTest {

    private static final String TENANT = "tenant-1";

    // ── constructor-injected collaborators (all 28) ─────────────────────
    @Mock private WorkflowBuilderSessionManager sessionManager;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowDraftAutoSaver draftAutoSaver;
    @Mock private WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock private WorkflowBuilderLogger buildLogger;
    @Mock private WorkflowCrudModule crudModule;
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
    @Mock private RunSignalResolutionService runSignalResolution;
    @Mock private WorkflowPlanVersionService planVersionService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private AgentDefaultsConfig agentDefaults;
    @Mock private ConversationEventPublisher conversationEventPublisher;

    @InjectMocks private WorkflowBuilderProvider provider;

    // ── standalone mocks (not provider fields) ──────────────────────────
    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        // The two enrichers wrap the success path of execute() / the delegates. Echo arg0
        // so tests observe the raw dispatch result.
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(resultEnricher.enrichResult(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Default: a healthy session resolves for any delegate that asks for one.
        lenient().when(sessionManager.getSession(any(), any(), any()))
                .thenReturn(new SessionResult(session, null));
        lenient().when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        // APPLICATION-immutability guard: no loaded session by default → pass-through.
        lenient().when(sessionStore.getSessionForConversation(any(), any()))
                .thenReturn(Optional.empty());

        // add_node defaults: node type enabled, no schema doc (validation skipped).
        lenient().when(nodeTypeSearchService.isNodeTypeEnabled(anyString())).thenReturn(true);
        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
        lenient().when(nodeLibraryService.getNodeTypesMap()).thenReturn(Map.of());
        lenient().when(agentDefaults.getMaxPerResourcePerTurn()).thenReturn(25);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ToolExecutionResult exec(Map<String, Object> params) {
        return provider.execute("workflow", params, ToolExecutionContext.of(TENANT));
    }

    private ToolExecutionResult exec(Map<String, Object> params, ToolExecutionContext ctx) {
        return provider.execute("workflow", params, ctx);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ToolExecutionContext ctxWithCredentials(Map<String, Object> creds) {
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    private static ToolExecutionResult okMap() {
        return ToolExecutionResult.success(new LinkedHashMap<>(Map.of("ok", true)));
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("metadata + top-level execute() framing")
    class Framing {

        @Test
        @DisplayName("getCategory() is WORKFLOW and getTools() delegates to the factory")
        void metadata() {
            AgentToolDefinition def = org.mockito.Mockito.mock(AgentToolDefinition.class);
            when(def.name()).thenReturn("workflow");
            when(toolDefinitionFactory.buildToolDefinition()).thenReturn(def);

            assertThat(provider.getCategory()).isEqualTo(ToolCategory.WORKFLOW);
            assertThat(provider.getTools()).extracting(AgentToolDefinition::name)
                    .containsExactly("workflow");
        }

        @Test
        @DisplayName("an unknown tool name → TOOL_NOT_FOUND")
        void unknownTool() {
            ToolExecutionResult r = provider.execute("not_workflow", params("action", "init"),
                    ToolExecutionContext.of(TENANT));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("a missing action → MISSING_PARAMETER")
        void missingAction() {
            ToolExecutionResult r = exec(params());
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("'action'");
        }

        @Test
        @DisplayName("a blank action → MISSING_PARAMETER")
        void blankAction() {
            assertThat(exec(params("action", "   ")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("an unrecognized action → INVALID_ENUM_VALUE")
        void invalidAction() {
            ToolExecutionResult r = exec(params("action", "frobnicate"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
            assertThat(r.error()).contains("frobnicate");
        }

        @Test
        @DisplayName("a write action on a read-only workflow agent → PERMISSION_DENIED")
        void readOnlyAccessModeDeniesWrite() {
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("workflowAccessMode", "read"));
            ToolExecutionResult r = exec(params("action", "add_node", "type", "agent", "label", "A"), ctx);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            // Dispatch must never be reached when access is denied up front.
            verify(creator, never()).executeAddAgent(any(), any());
        }

        @Test
        @DisplayName("a read action is allowed even for a read-only workflow agent")
        void readOnlyAccessModeAllowsRead() {
            when(helpModule.execute(eq("help"), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(okMap()));
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("workflowAccessMode", "read"));
            assertThat(exec(params("action", "help"), ctx).success()).isTrue();
        }

        @Test
        @DisplayName("a dispatch that throws is caught → EXECUTION_FAILED (no leak)")
        void dispatchExceptionIsCaught() {
            when(viewer.executeValidate(any())).thenThrow(new RuntimeException("boom"));
            ToolExecutionResult r = exec(params("action", "validate"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("boom");
        }

        @Test
        @DisplayName("a successful modifying action triggers the draft auto-save")
        void successfulModifyingActionAutoSaves() {
            when(connectionManager.executeConnect(any(), any())).thenReturn(okMap());
            ToolExecutionResult r = exec(params("action", "connect", "from", "A", "to", "B"));
            assertThat(r.success()).isTrue();
            verify(draftAutoSaver).autoSaveDraft(any(), eq(TENANT), eq(null));
        }

        @Test
        @DisplayName("a successful read-only action does NOT auto-save")
        void successfulReadOnlyActionSkipsAutoSave() {
            when(viewer.executeValidate(any())).thenReturn(okMap());
            assertThat(exec(params("action", "validate")).success()).isTrue();
            verify(draftAutoSaver, never()).autoSaveDraft(any(), any(), any());
        }

        @Test
        @DisplayName("an action alias (status → describe) resolves to the canonical handler")
        void actionAliasResolves() {
            when(viewer.executeGetSummary(any())).thenReturn(okMap());
            assertThat(exec(params("action", "status")).success()).isTrue();
            verify(viewer).executeGetSummary(session);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("APPLICATION immutability gate")
    class ApplicationImmutability {

        private ToolExecutionContext ctxWithConversation() {
            return ctxWithCredentials(Map.of("conversationId", "conv-1"));
        }

        @Test
        @DisplayName("a plan-mutating action on a loaded APPLICATION session → RESOURCE_CONFLICT")
        void mutatingActionOnApplicationRejected() {
            when(session.getLoadedWorkflowId()).thenReturn("app-7");
            when(session.isLoadedWorkflowIsApplication()).thenReturn(true);
            when(sessionStore.getSessionForConversation(TENANT, "conv-1"))
                    .thenReturn(Optional.of(session));

            ToolExecutionResult r = exec(
                    params("action", "add_node", "type", "agent", "label", "A"),
                    ctxWithConversation());

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
            assertThat(r.metadata())
                    .containsEntry("code", "APPLICATION_PLAN_IMMUTABLE")
                    .containsEntry("workflow_id", "app-7");
            verify(creator, never()).executeAddAgent(any(), any());
        }

        @Test
        @DisplayName("a pure-read action on a loaded APPLICATION session is NOT rejected")
        void readActionOnApplicationAllowed() {
            // read_rows is excluded from PLAN_MUTATING_ACTIONS so the guard never runs.
            when(tableOperations.execute(any(), any(), eq(TENANT), eq("read_rows"))).thenReturn(okMap());
            ToolExecutionResult r = exec(params("action", "read_rows"), ctxWithConversation());
            assertThat(r.success()).isTrue();
            verify(tableOperations).execute(any(), any(), eq(TENANT), eq("read_rows"));
        }

        @Test
        @DisplayName("a plan-mutating action on a regular WORKFLOW session passes the gate")
        void mutatingActionOnRegularWorkflowAllowed() {
            when(session.getLoadedWorkflowId()).thenReturn("wf-9");
            when(session.isLoadedWorkflowIsApplication()).thenReturn(false);
            when(sessionStore.getSessionForConversation(TENANT, "conv-1"))
                    .thenReturn(Optional.of(session));
            when(creator.executeAddAgent(any(), any())).thenReturn(okMap());

            ToolExecutionResult r = exec(
                    params("action", "add_node", "type", "agent", "label", "A"),
                    ctxWithConversation());
            assertThat(r.success()).isTrue();
            verify(creator).executeAddAgent(any(), any());
        }

        @Test
        @DisplayName("a benign guard-lookup failure is swallowed and the action proceeds (no dirty-state block)")
        void guardLookupErrorPassesThrough() {
            when(sessionStore.getSessionForConversation(any(), any()))
                    .thenThrow(new IllegalArgumentException("malformed stored id"));
            when(creator.executeAddAgent(any(), any())).thenReturn(okMap());

            ToolExecutionResult r = exec(
                    params("action", "add_node", "type", "agent", "label", "A"),
                    ctxWithConversation());
            assertThat(r.success()).isTrue();
            verify(creator).executeAddAgent(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("a blank tenant → MISSING_PARAMETER")
        void blankTenant() {
            ToolExecutionResult r = provider.execute("workflow", params("action", "init"),
                    ToolExecutionContext.of(""));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("init while viewing a workflow without force → guidance error")
        void viewingWithoutForce() {
            ToolExecutionContext viewing = new ToolExecutionContext(
                    TENANT, Map.of(), Map.of(), Set.of(), "wf-view", "My View", null, null);
            ToolExecutionResult r = exec(params("action", "init"), viewing);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("My View");
        }

        @Test
        @DisplayName("init with an existing session without force → active-session error")
        void existingSessionWithoutForce() {
            when(session.getTriggers()).thenReturn(new ArrayList<>());
            when(session.getMcps()).thenReturn(new ArrayList<>());
            when(session.getCores()).thenReturn(new ArrayList<>());
            when(session.getWorkflowName()).thenReturn("Existing");
            when(sessionStore.getSessionForConversation(any(), any())).thenReturn(Optional.of(session));

            ToolExecutionResult r = exec(params("action", "init"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Active session found").contains("Existing");
        }

        @Test
        @DisplayName("a fresh init creates a draft and returns OK + visualization metadata")
        void freshInit() {
            when(draftAutoSaver.createDraft(any(), eq(TENANT))).thenReturn("draft-99");

            ToolExecutionResult r = exec(params("action", "init", "name", "My Flow"));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "OK").containsEntry("draft_id", "draft-99");
            assertThat(r.metadata()).containsEntry("draftId", "draft-99");
            assertThat(r.metadata()).containsKey("visualization");
            verify(sessionManager).save(any());
            verify(buildLogger).logSessionStart(any());
        }

        @Test
        @DisplayName("init with force discards the existing session instead of erroring")
        void forceDiscardsExisting() {
            when(draftAutoSaver.createDraft(any(), eq(TENANT))).thenReturn("draft-1");
            // conversationId is null → discardAllForTenant path
            ToolExecutionResult r = exec(params("action", "init", "force", true));
            assertThat(r.success()).isTrue();
            verify(sessionManager).discardAllForTenant(TENANT);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("add_node routing")
    class AddNode {

        @Test
        @DisplayName("missing type → MISSING_PARAMETER")
        void missingType() {
            ToolExecutionResult r = exec(params("action", "add_node", "label", "X"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("type");
        }

        @Test
        @DisplayName("missing label → MISSING_PARAMETER")
        void missingLabel() {
            ToolExecutionResult r = exec(params("action", "add_node", "type", "agent"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("label");
        }

        @Test
        @DisplayName("type='trigger' is rejected with a hint to use a specific trigger type")
        void genericTriggerRejected() {
            ToolExecutionResult r = exec(params("action", "add_node", "type", "trigger", "label", "T"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("not valid");
        }

        @Test
        @DisplayName("a concrete trigger type routes to the trigger creator with trigger_type set")
        void triggerRouting() {
            when(creator.executeAddTrigger(any(), any(), eq(TENANT))).thenReturn(okMap());
            ToolExecutionResult r = exec(params("action", "add_node", "type", "form", "label", "My Form"));
            assertThat(r.success()).isTrue();

            ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
            verify(creator).executeAddTrigger(eq(session), captor.capture(), eq(TENANT));
            assertThat(captor.getValue()).containsEntry("trigger_type", "form");
        }

        @Test
        @DisplayName("type='agent' routes to the agent creator")
        void agentRouting() {
            when(creator.executeAddAgent(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "add_node", "type", "agent", "label", "A")).success()).isTrue();
            verify(creator).executeAddAgent(eq(session), any());
        }

        @Test
        @DisplayName("type='decision' routes to the decision creator")
        void decisionRouting() {
            when(creator.executeAddDecision(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "add_node", "type", "decision", "label", "D")).success()).isTrue();
            verify(creator).executeAddDecision(eq(session), any());
        }

        @Test
        @DisplayName("a table node type routes to table operations")
        void tableNodeRouting() {
            when(tableOperations.execute(any(), any(), eq(TENANT), eq("insert_row"))).thenReturn(okMap());
            assertThat(exec(params("action", "add_node", "type", "insert_row", "label", "Save")).success()).isTrue();
            verify(tableOperations).execute(eq(session), any(), eq(TENANT), eq("insert_row"));
        }

        @Test
        @DisplayName("an unknown type is treated as an MCP tool UUID (default branch)")
        void unknownTypeRoutesToMcp() {
            String toolUuid = UUID.randomUUID().toString();
            when(creator.executeAddMcp(any(), any(), eq(toolUuid))).thenReturn(okMap());
            assertThat(exec(params("action", "add_node", "type", toolUuid, "label", "Call")).success()).isTrue();
            verify(creator).executeAddMcp(eq(session), any(), eq(toolUuid));
        }

        @Test
        @DisplayName("type='mcp' + tool_id is rewritten to the resolved MCP node")
        void mcpAliasRewrite() {
            String toolUuid = UUID.randomUUID().toString();
            when(creator.executeAddMcp(any(), any(), eq(toolUuid))).thenReturn(okMap());
            assertThat(exec(params("action", "add_node", "type", "mcp", "label", "X", "tool_id", toolUuid))
                    .success()).isTrue();
            verify(creator).executeAddMcp(eq(session), any(), eq(toolUuid));
        }

        @Test
        @DisplayName("invalid node params → VALIDATION_ERROR with structured metadata")
        void validationError() {
            when(nodeLibraryService.findByType("decision"))
                    .thenReturn(Optional.of(org.mockito.Mockito.mock(NodeTypeDocumentationEntity.class)));
            when(nodeParamsValidator.validate(eq("decision"), any())).thenReturn(
                    ValidationResult.invalid(
                            List.of(new ValidationError("condition", "REQUIRED", "condition is required")),
                            List.of("add a 'condition' param")));

            ToolExecutionResult r = exec(params(
                    "action", "add_node", "type", "decision", "label", "D",
                    "params", new HashMap<>(Map.of("foo", "bar"))));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.metadata()).containsEntry("node_type", "decision").containsKey("validation_errors");
            assertThat(r.error()).contains("condition is required");
            verify(creator, never()).executeAddDecision(any(), any());
        }

        @Test
        @DisplayName("a disabled node type → EXECUTION_FAILED")
        void disabledNodeType() {
            when(nodeTypeSearchService.isNodeTypeEnabled("decision")).thenReturn(false);
            ToolExecutionResult r = exec(params("action", "add_node", "type", "decision", "label", "D"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("disabled");
        }

        @Test
        @DisplayName("a creator that throws is caught → EXECUTION_FAILED with a help pointer")
        void creatorExceptionCaught() {
            when(creator.executeAddAgent(any(), any())).thenThrow(new RuntimeException("kaboom"));
            ToolExecutionResult r = exec(params("action", "add_node", "type", "agent", "label", "A"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("kaboom").contains("help");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("session-bound delegates")
    class Delegates {

        @Test
        @DisplayName("a session-lookup error short-circuits the delegate")
        void sessionErrorPropagates() {
            ToolExecutionResult sessionErr =
                    ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No active session.");
            when(sessionManager.getSession(any(), any(), any()))
                    .thenReturn(new SessionResult(null, sessionErr));

            ToolExecutionResult r = exec(params("action", "connect", "from", "A", "to", "B"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(connectionManager, never()).executeConnect(any(), any());
        }

        @Test
        @DisplayName("connect → ConnectionManager.executeConnect")
        void connect() {
            when(connectionManager.executeConnect(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "connect", "from", "A", "to", "B")).success()).isTrue();
            verify(connectionManager).executeConnect(eq(session), any());
        }

        @Test
        @DisplayName("disconnect → ConnectionManager.executeDisconnect")
        void disconnect() {
            when(connectionManager.executeDisconnect(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "disconnect", "from", "A", "to", "B")).success()).isTrue();
            verify(connectionManager).executeDisconnect(eq(session), any());
        }

        @Test
        @DisplayName("modify → Modifier.executeModifyNode")
        void modify() {
            when(modifier.executeModifyNode(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "modify", "node", "A")).success()).isTrue();
            verify(modifier).executeModifyNode(eq(session), any());
        }

        @Test
        @DisplayName("remove → Modifier.executeRemove")
        void remove() {
            when(modifier.executeRemove(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "remove", "node", "A")).success()).isTrue();
            verify(modifier).executeRemove(eq(session), any());
        }

        @Test
        @DisplayName("undo → Modifier.executeUndo")
        void undo() {
            when(modifier.executeUndo(any())).thenReturn(okMap());
            assertThat(exec(params("action", "undo")).success()).isTrue();
            verify(modifier).executeUndo(session);
        }

        @Test
        @DisplayName("describe with a node param → Viewer.executeDescribe")
        void describeNode() {
            when(viewer.executeDescribe(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "describe", "node", "A")).success()).isTrue();
            verify(viewer).executeDescribe(eq(session), any());
        }

        @Test
        @DisplayName("describe with no node param → Viewer.executeGetSummary")
        void describeSummary() {
            when(viewer.executeGetSummary(any())).thenReturn(okMap());
            assertThat(exec(params("action", "describe")).success()).isTrue();
            verify(viewer).executeGetSummary(session);
        }

        @Test
        @DisplayName("validate → Viewer.executeValidate")
        void validate() {
            when(viewer.executeValidate(any())).thenReturn(okMap());
            assertThat(exec(params("action", "validate")).success()).isTrue();
            verify(viewer).executeValidate(session);
        }

        @Test
        @DisplayName("get_plan → PlanExporter.executeGetPlan")
        void getPlan() {
            when(planExporter.executeGetPlan(any())).thenReturn(okMap());
            assertThat(exec(params("action", "get_plan")).success()).isTrue();
            verify(planExporter).executeGetPlan(session);
        }

        @Test
        @DisplayName("set_plan → PlanExporter.executeSetPlan")
        void setPlan() {
            when(planExporter.executeSetPlan(any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "set_plan", "plan", Map.of())).success()).isTrue();
            verify(planExporter).executeSetPlan(eq(session), any());
        }

        @Test
        @DisplayName("discard → Loader.executeDiscard")
        void discard() {
            when(loader.executeDiscard(any())).thenReturn(okMap());
            assertThat(exec(params("action", "discard")).success()).isTrue();
            verify(loader).executeDiscard(session);
        }

        @Test
        @DisplayName("save → Loader.executeSave with name/description overrides applied")
        void save() {
            when(loader.executeSave(any())).thenReturn(okMap());
            assertThat(exec(params("action", "save", "name", "New Name", "description", "desc")).success()).isTrue();
            verify(session).setWorkflowName("New Name");
            verify(session).setWorkflowDescription("desc");
            verify(loader).executeSave(session);
        }

        @Test
        @DisplayName("a table operation action routes through table operations")
        void tableOperationDelegate() {
            when(tableOperations.execute(any(), any(), eq(TENANT), eq("find_rows"))).thenReturn(okMap());
            // 'find' is an alias of find_rows
            assertThat(exec(params("action", "find")).success()).isTrue();
            verify(tableOperations).execute(eq(session), any(), eq(TENANT), eq("find_rows"));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("load (read action, allow-list gated)")
    class Load {

        @Test
        @DisplayName("load delegates to Loader.executeLoad")
        void loadDelegates() {
            when(loader.executeLoad(eq(TENANT), any(), any(), any())).thenReturn(okMap());
            assertThat(exec(params("action", "load", "id", "wf-1")).success()).isTrue();
            verify(loader).executeLoad(eq(TENANT), any(), any(), any());
        }

        @Test
        @DisplayName("load of a workflow NOT in the agent's allow-list → PERMISSION_DENIED")
        void loadOutsideAllowListDenied() {
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("allowedWorkflowIds", List.of("other-wf")));
            ToolExecutionResult r = exec(params("action", "load", "id", "secret-wf"), ctx);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(loader, never()).executeLoad(any(), any(), any(), any());
        }

        @Test
        @DisplayName("load of a workflow IN the agent's allow-list proceeds")
        void loadInsideAllowListAllowed() {
            when(loader.executeLoad(eq(TENANT), any(), any(), any())).thenReturn(okMap());
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("allowedWorkflowIds", List.of("ok-wf")));
            assertThat(exec(params("action", "load", "id", "ok-wf"), ctx).success()).isTrue();
            verify(loader).executeLoad(eq(TENANT), any(), any(), any());
        }

        @Test
        @DisplayName("describe of an id outside the allow-list is gated too → PERMISSION_DENIED")
        void describeOutsideAllowListDenied() {
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("allowedWorkflowIds", List.of("other-wf")));
            ToolExecutionResult r = exec(params("action", "describe", "id", "secret-wf"), ctx);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(viewer, never()).executeDescribe(any(), any());
            verify(viewer, never()).executeGetSummary(any());
        }

        @Test
        @DisplayName("get_plan of a workflow_id outside the allow-list is gated → PERMISSION_DENIED")
        void getPlanOutsideAllowListDenied() {
            ToolExecutionContext ctx = ctxWithCredentials(Map.of("allowedWorkflowIds", List.of("other-wf")));
            ToolExecutionResult r = exec(params("action", "get_plan", "workflow_id", "secret-wf"), ctx);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(planExporter, never()).executeGetPlan(any());
        }

        @Test
        @DisplayName("a load that returns a session_id enriches the result via the resolved session")
        void loadEnrichesWhenSessionIdReturned() {
            Map<String, Object> loadData = new HashMap<>(Map.of("session_id", "s-load"));
            when(loader.executeLoad(eq(TENANT), any(), any(), any()))
                    .thenReturn(ToolExecutionResult.success(loadData));
            when(sessionStore.get("s-load")).thenReturn(Optional.of(session));

            assertThat(exec(params("action", "load", "id", "wf-1")).success()).isTrue();
            verify(sessionStore).get("s-load");
            verify(resultEnricher).enrichResult(any(), eq(session));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("CRUD delegation")
    class Crud {

        @Test
        @DisplayName("get/list/delete/runs/get_run/get_node_output/pin/unpin/publish/unpublish reach the CRUD module")
        void crudActionsDelegate() {
            for (String action : List.of("get", "list", "delete", "runs", "get_run",
                    "get_node_output", "pin", "unpin", "publish", "unpublish")) {
                when(crudModule.execute(eq(action), any(), eq(TENANT), any())).thenReturn(Optional.of(okMap()));
                assertThat(exec(params("action", action, "id", "wf-1")).success())
                        .as("action=%s", action).isTrue();
                verify(crudModule).execute(eq(action), any(), eq(TENANT), any());
            }
        }

        @Test
        @DisplayName("an empty CRUD module result → EXECUTION_FAILED fallback")
        void crudEmptyFallback() {
            when(crudModule.execute(eq("list"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "list"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("CRUD module failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("finish")
    class Finish {

        @Test
        @DisplayName("finish of an edit-in-progress (loaded id) delegates to save and closes the session")
        void finishExistingDelegatesToSave() {
            when(session.getLoadedWorkflowId()).thenReturn("wf-edit");
            when(session.getWorkflowName()).thenReturn("Edited");
            when(session.getSessionId()).thenReturn("sess-1");
            when(loader.executeSave(any())).thenReturn(ToolExecutionResult.success(new LinkedHashMap<>()));

            ToolExecutionResult r = exec(params("action", "finish"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "FINISHED")
                    .containsEntry("outcome", "EXISTING_WORKFLOW_UPDATED")
                    .containsEntry("session_state", "CLOSED");
            verify(sessionManager).delete("sess-1");
        }

        @Test
        @DisplayName("finish of a new workflow that fails validation → EXECUTION_FAILED")
        void finishNewValidationFails() {
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(viewer.executeValidate(any())).thenReturn(
                    ToolExecutionResult.success(new HashMap<>(Map.of("can_create", false))));

            ToolExecutionResult r = exec(params("action", "finish"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("validation errors");
        }

        @Test
        @DisplayName("finish of a valid new workflow persists it and returns the new id")
        void finishNewHappyPath() {
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.getWorkflowName()).thenReturn("Brand New");
            when(session.getSessionId()).thenReturn("sess-2");
            when(session.getOrgId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(new HashMap<>(Map.of(
                    "triggers", new ArrayList<>(), "mcps", new ArrayList<>(),
                    "cores", new ArrayList<>(), "edges", new ArrayList<>())));
            when(viewer.executeValidate(any())).thenReturn(
                    ToolExecutionResult.success(new HashMap<>(Map.of("can_create", true))));

            UUID newId = UUID.randomUUID();
            WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
            when(wf.getId()).thenReturn(newId);
            WorkflowManagementService.SaveResult sr =
                    org.mockito.Mockito.mock(WorkflowManagementService.SaveResult.class);
            when(sr.getWorkflow()).thenReturn(wf);
            when(workflowService.saveWorkflow(any(), any(), any(), any())).thenReturn(sr);

            ToolExecutionResult r = exec(params("action", "finish"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "FINISHED")
                    .containsEntry("outcome", "NEW_WORKFLOW_CREATED")
                    .containsEntry("workflow_id", newId.toString());
            verify(sessionManager).delete("sess-2");
        }

        @Test
        @DisplayName("finishing a new workflow auto-grants the creating agent access to it")
        void finishNewAutoGrantsAccess() {
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.getWorkflowName()).thenReturn("Granted");
            when(session.getSessionId()).thenReturn("sess-g");
            when(session.buildPlanMap()).thenReturn(new HashMap<>(Map.of("triggers", new ArrayList<>())));
            when(viewer.executeValidate(any())).thenReturn(
                    ToolExecutionResult.success(new HashMap<>(Map.of("can_create", true))));

            UUID newId = UUID.randomUUID();
            WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
            when(wf.getId()).thenReturn(newId);
            WorkflowManagementService.SaveResult sr =
                    org.mockito.Mockito.mock(WorkflowManagementService.SaveResult.class);
            when(sr.getWorkflow()).thenReturn(wf);
            when(workflowService.saveWorkflow(any(), any(), any(), any())).thenReturn(sr);

            // A restricted agent: its allow-list must gain the id it just created.
            Map<String, Object> creds = new HashMap<>();
            creds.put("allowedWorkflowIds", new ArrayList<>(List.of("existing-wf")));

            ToolExecutionResult r = exec(params("action", "finish"), ctxWithCredentials(creds));
            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> granted = (List<String>) creds.get("allowedWorkflowIds");
            assertThat(granted).containsExactly("existing-wf", newId.toString());
        }

        @Test
        @DisplayName("a plan-limit rejection during create → EXTERNAL_SERVICE_ERROR (do-not-retry)")
        void finishNewLimitExceeded() {
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(new HashMap<>(Map.of("triggers", new ArrayList<>())));
            when(viewer.executeValidate(any())).thenReturn(
                    ToolExecutionResult.success(new HashMap<>(Map.of("can_create", true))));
            when(workflowService.saveWorkflow(any(), any(), any(), any()))
                    .thenThrow(org.mockito.Mockito.mock(
                            com.apimarketplace.auth.client.entitlement.LimitExceededException.class));

            ToolExecutionResult r = exec(params("action", "finish"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
        }

        @Test
        @DisplayName("the per-turn create cap blocks a second new-workflow finish in the same turn")
        void finishNewPerTurnLimit() {
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.getWorkflowName()).thenReturn("Capped");
            when(session.getSessionId()).thenReturn("sess-3");
            when(session.buildPlanMap()).thenReturn(new HashMap<>(Map.of(
                    "triggers", new ArrayList<>(), "mcps", new ArrayList<>())));
            when(viewer.executeValidate(any())).thenReturn(
                    ToolExecutionResult.success(new HashMap<>(Map.of("can_create", true))));
            when(agentDefaults.getMaxPerResourcePerTurn()).thenReturn(1);

            UUID newId = UUID.randomUUID();
            WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
            when(wf.getId()).thenReturn(newId);
            WorkflowManagementService.SaveResult sr =
                    org.mockito.Mockito.mock(WorkflowManagementService.SaveResult.class);
            when(sr.getWorkflow()).thenReturn(wf);
            when(workflowService.saveWorkflow(any(), any(), any(), any())).thenReturn(sr);

            ToolExecutionContext ctx = ctxWithCredentials(Map.of("turnId", "turn-X"));

            assertThat(exec(params("action", "finish"), ctx).success()).isTrue();
            ToolExecutionResult second = exec(params("action", "finish"), ctx);
            assertThat(second.success()).isFalse();
            assertThat(second.error()).contains("LIMIT REACHED");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("a query search delegates to NodeTypeSearchService.search")
        void searchByQuery() {
            when(nodeTypeSearchService.search(eq("http"), any())).thenReturn(List.of(Map.of("type", "http_request")));
            ToolExecutionResult r = exec(params("action", "search", "query", "http"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("results");
            verify(nodeTypeSearchService).search(eq("http"), any());
        }

        @Test
        @DisplayName("a category search delegates to NodeTypeSearchService.searchByCategory")
        void searchByCategory() {
            when(nodeTypeSearchService.searchByCategory(any(), eq("core"), any()))
                    .thenReturn(List.of(Map.of("type", "decision")));
            ToolExecutionResult r = exec(params("action", "search", "category", "core"));
            assertThat(r.success()).isTrue();
            verify(nodeTypeSearchService).searchByCategory(any(), eq("core"), any());
        }

        @Test
        @DisplayName("an empty search returns the full categories + grouped node-type listing")
        void searchEmptyListsEverything() {
            when(nodeTypeSearchService.getCategories()).thenReturn(List.of(Map.of("id", "core")));
            when(nodeTypeSearchService.getAllGroupedByCategory()).thenReturn(Map.of("core", List.of()));
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("categories").containsKey("node_types");
        }

        @Test
        @DisplayName("a search backend failure → EXTERNAL_SERVICE_ERROR")
        void searchFailure() {
            when(nodeTypeSearchService.getCategories()).thenThrow(new RuntimeException("down"));
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("help delegates to the help module")
        void helpDelegates() {
            when(helpModule.execute(eq("help"), any(), eq(TENANT), any())).thenReturn(Optional.of(okMap()));
            assertThat(exec(params("action", "help")).success()).isTrue();
            verify(helpModule).execute(eq("help"), any(), eq(TENANT), any());
        }

        @Test
        @DisplayName("an empty help-module result → EXECUTION_FAILED fallback")
        void helpEmptyFallback() {
            when(helpModule.execute(eq("help"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "help"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Help module failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("execute (run a workflow)")
    class ExecuteWorkflow {

        /** A workflow visible to the caller (tenant match, no org → ScopeGuard strict-scope passes). */
        private WorkflowEntity inScopeWorkflow(UUID id, Map<String, Object> plan) {
            WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
            lenient().when(wf.getTenantId()).thenReturn(TENANT);
            lenient().when(wf.getOrganizationId()).thenReturn(null);
            lenient().when(wf.getName()).thenReturn("My WF");
            lenient().when(wf.getPlan()).thenReturn(plan);
            when(workflowService.getWorkflow(id)).thenReturn(Optional.of(wf));
            return wf;
        }

        private Map<String, Object> planWithTrigger() {
            return new HashMap<>(Map.of("triggers",
                    new ArrayList<>(List.of(new HashMap<>(Map.of("id", "trigger:t1", "label", "T1", "type", "manual"))))));
        }

        @Test
        @DisplayName("no id and no loaded session → EXECUTION_FAILED guidance")
        void noIdNoSession() {
            // session has no loaded workflow id → resolveWorkflowId returns null
            when(session.getLoadedWorkflowId()).thenReturn(null);
            ToolExecutionResult r = exec(params("action", "execute"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("load");
        }

        @Test
        @DisplayName("a non-existent workflow id → WORKFLOW_NOT_FOUND")
        void workflowNotFound() {
            UUID id = UUID.randomUUID();
            when(workflowService.getWorkflow(id)).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString()));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_NOT_FOUND);
        }

        @Test
        @DisplayName("a workflow owned by another tenant → WORKFLOW_NOT_FOUND (no cross-tenant run)")
        void workflowOutsideScope() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = org.mockito.Mockito.mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("someone-else");
            lenient().when(wf.getOrganizationId()).thenReturn(null);
            when(workflowService.getWorkflow(id)).thenReturn(Optional.of(wf));
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString()));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_NOT_FOUND);
        }

        @Test
        @DisplayName("a malformed workflow id → EXECUTION_FAILED (UUID parse caught)")
        void malformedId() {
            ToolExecutionResult r = exec(params("action", "execute", "id", "not-a-uuid"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("CURRENT mode on a workflow with no triggers → WORKFLOW_INVALID")
        void currentModeNoTriggers() {
            UUID id = UUID.randomUUID();
            inScopeWorkflow(id, new HashMap<>());  // empty plan → no triggers
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString()));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            assertThat(r.error()).contains("no triggers");
        }

        @Test
        @DisplayName("version=0 → EXECUTION_FAILED (resolveVersionSelection rejects non-positive)")
        void versionZeroRejected() {
            UUID id = UUID.randomUUID();
            inScopeWorkflow(id, new HashMap<>());
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString(), "version", 0));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("positive integer");
        }

        @Test
        @DisplayName("version='abc' → EXECUTION_FAILED (non-numeric, non-'pinned' string)")
        void versionGarbageStringRejected() {
            UUID id = UUID.randomUUID();
            inScopeWorkflow(id, new HashMap<>());
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString(), "version", "abc"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Invalid 'version' value");
        }

        @Test
        @DisplayName("version of a wrong type (Map) → EXECUTION_FAILED")
        void versionWrongTypeRejected() {
            UUID id = UUID.randomUUID();
            inScopeWorkflow(id, new HashMap<>());
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString(), "version", Map.of()));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Invalid 'version' type");
        }

        @Test
        @DisplayName("REPLAY_VERSION when the version is absent from history → EXECUTION_FAILED")
        void replayVersionNotFound() {
            UUID id = UUID.randomUUID();
            inScopeWorkflow(id, new HashMap<>());
            when(planVersionService.getVersion(id, 2)).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString(), "version", 2));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Version 2 not found");
        }

        @Test
        @DisplayName("version='pinned' on an unpinned workflow → EXECUTION_FAILED")
        void pinnedButNotPinned() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = inScopeWorkflow(id, new HashMap<>());
            when(wf.getPinnedVersion()).thenReturn(null);
            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString(), "version", "pinned"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("not pinned");
        }

        @Test
        @DisplayName("a CURRENT-mode run fires the trigger and returns the built result + run viz")
        void currentModeFiresAndReturnsResult() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = inScopeWorkflow(id, planWithTrigger());

            WorkflowRunEntity run = org.mockito.Mockito.mock(WorkflowRunEntity.class);
            lenient().when(run.getRunIdPublic()).thenReturn("run-1");
            when(agentWorkflowFireService.createRun(eq(wf), any(), any(), eq(TENANT))).thenReturn(run);
            when(agentWorkflowFireService.hasOnlyBootstrapTriggers(any())).thenReturn(false);
            when(agentWorkflowFireService.resolveTrigger(any(), any()))
                    .thenReturn(new com.apimarketplace.orchestrator.domain.workflow.Trigger("t1", "T1", "single", "manual"));
            when(agentWorkflowFireService.buildResult(eq(run), any(), eq(wf), any(), eq(TENANT)))
                    .thenReturn(new LinkedHashMap<>(Map.of("status", "COMPLETED", "run_id", "run-1")));

            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString()));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "COMPLETED");
            assertThat(r.metadata()).containsKey("visualization");
            verify(agentWorkflowFireService).fire(eq(run), any(), any());
        }

        @Test
        @DisplayName("a bootstrap-only workflow short-circuits to a WAITING_TRIGGER seed run")
        void bootstrapOnlyShortCircuits() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = inScopeWorkflow(id, planWithTrigger());

            WorkflowRunEntity run = org.mockito.Mockito.mock(WorkflowRunEntity.class);
            lenient().when(run.getRunIdPublic()).thenReturn("seed-1");
            lenient().when(run.getPlanVersion()).thenReturn(1);
            when(agentWorkflowFireService.createRun(eq(wf), any(), any(), eq(TENANT))).thenReturn(run);
            when(agentWorkflowFireService.hasOnlyBootstrapTriggers(any())).thenReturn(true);

            ToolExecutionResult r = exec(params("action", "execute", "id", id.toString()));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "BOOTSTRAPPED").containsEntry("run_id", "seed-1");
            // The fire path must NOT run for a bootstrap-only workflow.
            verify(agentWorkflowFireService, never()).fire(any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resolve_approval / continue_interface (advance a paused run)")
    class PausedRunSignals {

        private static final String RUN = "run-abc";

        private void runInScope() {
            WorkflowRunEntity run = org.mockito.Mockito.mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(TENANT);
            lenient().when(run.getOrganizationId()).thenReturn(null);
            when(workflowRunRepository.findByRunIdPublic(RUN)).thenReturn(Optional.of(run));
        }

        private SignalWaitEntity pendingOn(String nodeId) {
            SignalWaitEntity w = org.mockito.Mockito.mock(SignalWaitEntity.class);
            lenient().when(w.getNodeId()).thenReturn(nodeId);
            return w;
        }

        @Test
        @DisplayName("resolve_approval without run_id → MISSING_PARAMETER")
        void approvalMissingRunId() {
            ToolExecutionResult r = exec(params("action", "resolve_approval", "decision", "approved"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("run_id");
        }

        @Test
        @DisplayName("resolve_approval on a run outside scope → RESOURCE_NOT_FOUND")
        void approvalRunNotFound() {
            when(workflowRunRepository.findByRunIdPublic(RUN)).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "approved"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("resolve_approval with a missing/invalid decision → MISSING_PARAMETER")
        void approvalBadDecision() {
            runInScope();
            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "maybe"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("approved");
        }

        @Test
        @DisplayName("resolve_approval with no pending approval → RESOURCE_NOT_FOUND")
        void approvalNonePending() {
            runInScope();
            when(runSignalResolution.pendingOfType(RUN, SignalType.USER_APPROVAL)).thenReturn(List.of());
            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "approved"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("resolve_approval with multiple pending approvals → MISSING_PARAMETER (node_id needed)")
        void approvalMultiplePending() {
            runInScope();
            List<SignalWaitEntity> pending = List.of(pendingOn("core:a"), pendingOn("core:b"));
            when(runSignalResolution.pendingOfType(RUN, SignalType.USER_APPROVAL)).thenReturn(pending);
            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "approved"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("node_id");
        }

        @Test
        @DisplayName("resolve_approval auto-resolves the single pending approval and returns resolved")
        void approvalHappyPath() {
            runInScope();
            List<SignalWaitEntity> pending = List.of(pendingOn("core:approve"));
            when(runSignalResolution.pendingOfType(RUN, SignalType.USER_APPROVAL)).thenReturn(pending);
            when(runSignalResolution.resolveApproval(eq(RUN), eq("core:approve"),
                    eq(SignalResolution.APPROVED), any(), eq(TENANT), any(), any()))
                    .thenReturn(new RunSignalResolutionService.Outcome(true, null, 1L, 0, "APPROVED"));

            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "approved"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "resolved").containsEntry("node_id", "core:approve");
        }

        @Test
        @DisplayName("resolve_approval surfaces an already-resolved signal as EXECUTION_FAILED")
        void approvalAlreadyResolved() {
            runInScope();
            List<SignalWaitEntity> pending = List.of(pendingOn("core:approve"));
            when(runSignalResolution.pendingOfType(RUN, SignalType.USER_APPROVAL)).thenReturn(pending);
            when(runSignalResolution.resolveApproval(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new RunSignalResolutionService.Outcome(false, "already_resolved", null, null, null));

            ToolExecutionResult r = exec(params("action", "resolve_approval", "run_id", RUN, "decision", "approve"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("already resolved");
        }

        @Test
        @DisplayName("continue_interface without run_id → MISSING_PARAMETER")
        void continueMissingRunId() {
            ToolExecutionResult r = exec(params("action", "continue_interface"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("continue_interface on a run outside scope → RESOURCE_NOT_FOUND")
        void continueRunNotFound() {
            when(workflowRunRepository.findByRunIdPublic(RUN)).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "continue_interface", "run_id", RUN));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("continue_interface with no paused interface → RESOURCE_NOT_FOUND")
        void continueNonePending() {
            runInScope();
            when(runSignalResolution.pendingOfType(RUN, SignalType.INTERFACE_SIGNAL)).thenReturn(List.of());
            ToolExecutionResult r = exec(params("action", "continue_interface", "run_id", RUN));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("continue_interface with multiple paused interfaces → MISSING_PARAMETER (node_id needed)")
        void continueMultiplePending() {
            runInScope();
            List<SignalWaitEntity> pending = List.of(pendingOn("interface:a"), pendingOn("interface:b"));
            when(runSignalResolution.pendingOfType(RUN, SignalType.INTERFACE_SIGNAL)).thenReturn(pending);
            ToolExecutionResult r = exec(params("action", "continue_interface", "run_id", RUN));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("node_id");
        }

        @Test
        @DisplayName("continue_interface auto-resolves the single paused interface and advances the run")
        void continueHappyPath() {
            runInScope();
            List<SignalWaitEntity> pending = List.of(pendingOn("interface:page1"));
            when(runSignalResolution.pendingOfType(RUN, SignalType.INTERFACE_SIGNAL)).thenReturn(pending);
            when(runSignalResolution.continueInterface(eq(RUN), eq("interface:page1"), any(), eq(TENANT), any(), any()))
                    .thenReturn(new RunSignalResolutionService.Outcome(true, null, 2L, 1, null));

            ToolExecutionResult r = exec(params("action", "continue_interface", "run_id", RUN));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("status", "resolved").containsEntry("node_id", "interface:page1");
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
