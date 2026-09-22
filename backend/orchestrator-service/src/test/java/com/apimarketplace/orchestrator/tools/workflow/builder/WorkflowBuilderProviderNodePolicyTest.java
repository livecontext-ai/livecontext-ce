package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Setting the execution policy at the moment a node is created, through the real {@code add_node}
 * dispatch.
 *
 * <p>Two things can only be seen here, and both are silent failures.
 *
 * <ol>
 *   <li><b>The policy must be LIFTED OUT of the call before the creator sees it.</b> On an mcp node
 *       the remaining keys are the endpoint's own arguments, so a policy still in the map is handed
 *       to the provider as a parameter it never declared. The node is created, the run is green,
 *       and the policy does nothing. The creator is a mock here precisely so the map it receives
 *       can be inspected.</li>
 *   <li><b>A policy the engine will refuse must be refused now.</b> The engine refuses some
 *       combinations at plan-parse time, so accepting one here gives the agent a node it can add
 *       and never run, with the error arriving on some later, unrelated call.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("workflow(action='add_node') - the per-node execution policy")
class WorkflowBuilderProviderNodePolicyTest {

    @Mock WorkflowBuilderSessionManager sessionManager;
    @Mock WorkflowBuilderResultEnricher resultEnricher;
    @Mock WorkflowDraftAutoSaver draftAutoSaver;
    @Mock WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock WorkflowBuilderLogger buildLogger;
    @Mock WorkflowCrudModule crudModule;
    @Mock WorkflowManagementService workflowService;
    @Mock InterfaceClient interfaceClient;
    @Mock NodeTypeSearchService nodeTypeSearchService;
    @Mock com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService adHocNodeExecutionService;
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
    @Mock WorkflowBuilderSessionStore sessionStore;

    private static final String TENANT_ID = "tenant-node-policy";
    /** A catalog tool id, which is what add_node takes as `type` for a tool step. */
    private static final String TOOL_ID = "9f1c2e64-0d4e-4a5f-9b7a-2c8e5d3f1a06";
    private static final ToolExecutionContext CTX = ToolExecutionContext.of(TENANT_ID);

    private WorkflowBuilderProvider provider;
    private WorkflowBuilderSession session;

    /** The map the creator was handed: what would have reached the provider as arguments. */
    private Map<String, Object> paramsSeenByCreator;

    @BeforeEach
    void setUp() {
        provider = new WorkflowBuilderProvider(
            sessionManager, resultEnricher, draftAutoSaver, toolDefinitionFactory, buildLogger,
            crudModule, workflowService, interfaceClient, nodeTypeSearchService, adHocNodeExecutionService,
            nodeLibraryService, nodeParamsValidator, workflowHelpProvider, creator, connectionManager,
            modifier, viewer, loader, tableOperations, planExporter, helpModule, executionService,
            workflowRunRepository, agentWorkflowFireService, runSignalResolution, planVersionService,
            productionRunResolver,
            new com.apimarketplace.orchestrator.config.AgentDefaultsConfig(),
            conversationEventPublisher, null /* mockOutputSuggester - not exercised here */
        );

        session = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId(TENANT_ID).workflowName("W")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        lenient().when(sessionManager.getSession(any(), eq(TENANT_ID), any()))
                .thenReturn(new WorkflowBuilderSessionManager.SessionResult(session, null));
        lenient().when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        lenient().when(resultEnricher.enrichResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(nodeTypeSearchService.isNodeTypeEnabled(anyString())).thenReturn(true);
        // No library entry = the schema validator is skipped, which keeps these tests about the
        // policy rather than about http_request's parameter schema.
        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    /**
     * A catalog tool step, keyed the way production keys one.
     *
     * <p>The type is a tool reference, which {@code add_node} accepts in THREE shapes: a UUID, a
     * prefixed UUID, and an {@code apiSlug/toolSlug} pair ({@code ToolSchemaFetcher} dispatches the
     * slug form explicitly, and the plan-format doc's own example uses it). The tests below cover
     * all three, because a check that recognised only the UUID refused the feature's primary use
     * through its primary door.
     *
     * <p>{@code http_request} is deliberately NOT used here: it is a CORE node
     * ({@code CreatorBase.NodeType.HTTP_REQUEST} carries the {@code core} prefix), so a fixture that
     * stored an {@code mcp:} id for it would assert success on a call production refuses.
     */
    @SuppressWarnings("unchecked")
    private void creatorAddsAnMcpNode() {
        lenient().when(creator.executeAddMcp(any(), any(), anyString())).thenAnswer(inv -> {
            paramsSeenByCreator = new LinkedHashMap<>((Map<String, Object>) inv.getArgument(1));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "mcp:fetch_page");
            node.put("type", "mcp");
            node.put("label", "Fetch Page");
            session.getMcps().add(node);
            session.setLastAddedNodeId("mcp:fetch_page");
            return ToolExecutionResult.success(Map.of("status", "OK"));
        });
    }

    /** A core node, which is what an http_request actually is. */
    private void creatorAddsACoreNode() {
        lenient().when(creator.executeAddHttpRequest(any(), any())).thenAnswer(inv -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:fetch_page");
            node.put("type", "http_request");
            node.put("label", "Fetch Page");
            session.getCores().add(node);
            session.setLastAddedNodeId("core:fetch_page");
            return ToolExecutionResult.success(Map.of("status", "OK"));
        });
    }

    /** Same, for a decision core: the node type the engine refuses continueOnFailure on. */
    private void creatorAddsADecisionCore() {
        lenient().when(creator.executeAddDecision(any(), any())).thenAnswer(inv -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:check");
            node.put("type", "decision");
            node.put("label", "Check");
            session.getCores().add(node);
            session.setLastAddedNodeId("core:check");
            return ToolExecutionResult.success(Map.of("status", "OK"));
        });
    }

    private Map<String, Object> addNode(String type, String label, Map<String, Object> params, Object policy) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("action", "add_node");
        call.put("type", type);
        call.put("label", label);
        if (params != null) call.put("params", new LinkedHashMap<>(params));
        if (policy != null) call.put("nodePolicy", policy);
        return call;
    }

    private Map<String, Object> createdNode() {
        return session.findNode("mcp:fetch_page").orElseThrow();
    }

    private Map<String, Object> createdCoreNode() {
        return session.findNode("core:fetch_page").orElseThrow();
    }

    @Test
    @DisplayName("the policy is written onto the node the creator just added")
    void policyLandsOnTheNewNode() {
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 2, "retryBackoffMs", 3000)),
                TENANT_ID, CTX);

        assertThat(result.success()).isTrue();
        assertThat(createdNode().get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "retryBackoffMs", 3000L));
    }

    @Test
    @DisplayName("the creator never sees the policy, so it cannot become a provider argument")
    void theCreatorNeverSeesThePolicy() {
        creatorAddsAnMcpNode();

        provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("providerRetryMaxWaitSec", 0)),
                TENANT_ID, CTX);

        assertThat(paramsSeenByCreator)
                .as("on an mcp node every key left here IS an endpoint argument")
                .doesNotContainKeys("nodePolicy", "node_policy", "executionPolicy", "execution_policy")
                .containsEntry("url", "https://example.test");
    }

    @Test
    @DisplayName("a policy nested inside params is honoured, and the copy the creator reads is clean")
    @SuppressWarnings("unchecked")
    void policyNestedInsideParamsIsHonoured() {
        // params is where a model puts everything else, so this is the shape that actually arrives.
        creatorAddsAnMcpNode();
        Map<String, Object> call = addNode(TOOL_ID, "Fetch Page", null, null);
        Map<String, Object> callersParams = new LinkedHashMap<>();
        callersParams.put("url", "https://example.test");
        callersParams.put("nodePolicy", Map.of("retryCount", 1));
        call.put("params", callersParams);

        provider.executeAddNode(call, TENANT_ID, CTX);

        assertThat(createdNode().get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 1));
        assertThat((Map<String, Object>) paramsSeenByCreator.get("params"))
                .as("the creator reads its fields out of this container, so the policy must be gone "
                        + "from it, not only from the root")
                .doesNotContainKey("nodePolicy")
                .containsEntry("url", "https://example.test");
        assertThat(callersParams)
                .as("the caller's own argument map is left alone: it may be immutable, and mutating "
                        + "it is a side effect nobody asked for")
                .containsKey("nodePolicy");
    }

    @Test
    @DisplayName("an IMMUTABLE params map carrying a policy is accepted, not an exception")
    void immutableParamsMapIsAccepted() {
        // Every tool-call argument map a test builds with Map.of is immutable, and so is any a
        // future caller builds that way. Mutating it in place threw.
        creatorAddsAnMcpNode();
        Map<String, Object> call = addNode(TOOL_ID, "Fetch Page", null, null);
        call.put("params", Map.of("url", "https://example.test", "nodePolicy", Map.of("retryCount", 1)));

        ToolExecutionResult result = provider.executeAddNode(call, TENANT_ID, CTX);

        assertThat(result.success()).isTrue();
        assertThat(createdNode().get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 1));
    }

    @Test
    @DisplayName("a node added without a policy carries none, so nothing changes for ordinary building")
    void noPolicyLeavesTheNodeUntouched() {
        creatorAddsAnMcpNode();

        provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"), null),
                TENANT_ID, CTX);

        assertThat(createdNode()).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a malformed policy is refused BEFORE the node exists, and says so")
    void malformedPolicyIsRefusedBeforeCreation() {
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", -1)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("retryCount").contains("No node was created");
        verify(creator, never()).executeAddMcp(any(), any(), anyString());
        assertThat(session.getMcps())
                .as("half a creation is worse than none: the agent cannot see the canvas")
                .isEmpty();
    }

    @Test
    @DisplayName("a policy the engine would refuse for this node TYPE is reported, and the node is "
            + "left runnable without it")
    void typeIncompatiblePolicyIsReportedAndNotStored() {
        // The type is only known once the creator has run, so the node exists by then. Leaving it
        // policy-less keeps the plan parseable, which matters more than a tidy all-or-nothing: an
        // unparseable plan cannot be opened to repair, while an unpoliced node takes one modify.
        creatorAddsADecisionCore();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("decision", "Check", Map.of("conditions", "x"), Map.of("continueOnFailure", true)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("was created")
                .contains("ALL its ports")
                .contains("action='modify'");
        assertThat(session.getCores().get(0))
                .as("storing it would make the workflow impossible to run, and the error would "
                        + "surface on a later, unrelated call")
                .doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("the session is saved when a policy was written, so it survives the next call")
    void theSessionIsSavedAfterWritingAPolicy() {
        creatorAddsAnMcpNode();

        provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 2)),
                TENANT_ID, CTX);

        verify(sessionStore).save(session);
    }

    @Test
    @DisplayName("the reply reports the policy that was stored, so the caller need not ask again")
    @SuppressWarnings("unchecked")
    void theReplyReportsThePolicy() {
        // modify reports it; add_node returning a bare success left the caller unable to tell an
        // applied policy from one that was coerced or dropped. It cannot see the canvas.
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 2, "providerRetryMaxWaitSec", 0)),
                TENANT_ID, CTX);

        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "providerRetryMaxWaitSec", 0));
        assertThat(String.valueOf(data.get("node_policy_hint"))).contains("every run");
    }

    @Test
    @DisplayName("a node added WITHOUT a policy reports none, so the reply does not grow a field")
    @SuppressWarnings("unchecked")
    void aNodeWithoutAPolicyReportsNone() {
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"), null),
                TENANT_ID, CTX);

        assertThat((Map<String, Object>) result.data()).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("nodePolicy={} on creation SUCCEEDS: it is the documented way to say 'no policy'")
    void anEmptyPolicyOnCreationSucceeds() {
        // The schema advertises nodePolicy={} to remove a policy, and add_node accepts the same
        // parameter. Reading "nothing was written" as "could not be written" failed a call that had
        // done exactly what was asked, on a node it had just created, and logged an ERROR for it.
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"), Map.of()),
                TENANT_ID, CTX);

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(createdNode()).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a policy of nothing but DEFAULTS succeeds the same way")
    void anAllDefaultPolicyOnCreationSucceeds() {
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 0, "continueOnFailure", false)),
                TENANT_ID, CTX);

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(createdNode())
                .as("an empty block is the absence of a policy, so nothing is stored")
                .doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a creator that stores no resolvable node id FAILS the call instead of dropping "
            + "the policy in silence")
    void anUnresolvableNodeIdIsReported() {
        // Not reachable through any creator today (all seven set the id in finalizeNode), which is
        // exactly why it needs pinning: it is one un-finalized creator away, and the old code
        // returned a cheerful success with the policy silently unwritten.
        lenient().when(creator.executeAddMcp(any(), any(), anyString())).thenAnswer(inv -> {
            session.setLastAddedNodeId(null);
            return ToolExecutionResult.success(Map.of("status", "OK"));
        });

        ToolExecutionResult result = provider.executeAddNode(
                addNode(TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 2)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .as("named by its LABEL, which is what the caller can act on - the raw type is a "
                        + "tool UUID it never chose")
                .contains("Node 'Fetch Page' was created")
                .contains("could not be written")
                .contains("action='modify'");
    }

    @Test
    @DisplayName("a provider-retry budget on a node that calls no provider is refused, and the node "
            + "is left runnable without it")
    void providerBudgetOnANonToolNodeIsRefused() {
        // Only StepNode carries this to the catalog. Accepted on a core node it would sit in the
        // plan looking configured while the platform kept retrying underneath the author.
        //
        // Checked AFTER creation on purpose: whether a type makes a provider call cannot be read off
        // the type (a tool arrives as a UUID, a prefixed UUID or apiSlug/toolSlug, and the switch
        // treats an unrecognised type AS a tool), and a pre-creation guess refused the feature's
        // primary use. So the node exists, without the policy, and the reply says exactly that.
        creatorAddsADecisionCore();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("decision", "Check", Map.of("conditions", "x"),
                        Map.of("providerRetryMaxWaitSec", 0)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("catalog tool step only")
                .contains("was created")
                .contains("action='modify'");
        assertThat(session.getCores().get(0))
                .as("left policy-less, so the plan still parses and can be repaired with one modify")
                .doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("http_request is a CORE node, so it is refused too - the type that looks most "
            + "like a provider call and is not a tool step")
    void providerBudgetOnAnHttpRequestNodeIsRefused() {
        creatorAddsACoreNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("http_request", "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("providerRetryMaxWaitSec", 0)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("catalog tool step only");
        assertThat(createdCoreNode()).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("an http_request node still takes the rest of the policy")
    void anHttpRequestNodeStillTakesARetry() {
        creatorAddsACoreNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("http_request", "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("retryCount", 2, "timeoutMs", 30000)),
                TENANT_ID, CTX);

        assertThat(result.success()).isTrue();
        assertThat(createdCoreNode().get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "timeoutMs", 30000L));
    }

    @Test
    @DisplayName("REGRESSION: a tool referenced by apiSlug/toolSlug accepts the budget")
    void aSlugTypedToolAcceptsTheBudget() {
        // add_node takes a tool as a UUID, as a prefixed UUID, or as apiSlug/toolSlug - and its
        // switch treats every UNRECOGNISED type as a tool. A pre-creation check that tried to read
        // "is this a tool" off the type refused this, the commonest hand-written form, and told the
        // agent to move the setting onto the node it was trying to create.
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("gmail/list-messages", "Fetch Page", Map.of("q", "is:unread"),
                        Map.of("providerRetryMaxWaitSec", 0)),
                TENANT_ID, CTX);

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(createdNode().get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("providerRetryMaxWaitSec", 0));
    }

    @Test
    @DisplayName("REGRESSION: a tool referenced by a PREFIXED uuid accepts it too")
    void aPrefixedUuidToolAcceptsTheBudget() {
        creatorAddsAnMcpNode();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("mcp:" + TOOL_ID, "Fetch Page", Map.of("url", "https://example.test"),
                        Map.of("providerRetryMaxWaitSec", 45)),
                TENANT_ID, CTX);

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(createdNode().get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("providerRetryMaxWaitSec", 45));
    }

    @Test
    @DisplayName("a policy on a TRIGGER type is refused before the trigger is created")
    void policyOnATriggerTypeIsRefused() {
        ToolExecutionResult result = provider.executeAddNode(
                addNode("webhook", "Start", Map.of(), Map.of("retryCount", 2)),
                TENANT_ID, CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("not available on trigger or note nodes")
                .contains("No node was created");
        assertThat(session.getTriggers()).isEmpty();
    }

    @Test
    @DisplayName("the same node still takes retryCount, so the refusal is narrow")
    void aNonToolNodeStillTakesARetry() {
        creatorAddsADecisionCore();

        ToolExecutionResult result = provider.executeAddNode(
                addNode("decision", "Check", Map.of("conditions", "x"), Map.of("retryCount", 2)),
                TENANT_ID, CTX);

        assertThat(result.success()).isTrue();
        assertThat(session.getCores().get(0).get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 2));
    }
}
