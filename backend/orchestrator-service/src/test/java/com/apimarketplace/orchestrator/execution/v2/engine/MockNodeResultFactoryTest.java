package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.SwitchNode;
import com.apimarketplace.orchestrator.services.impl.CatalogMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockNodeResultFactory} - envelope parity with real
 * executions, port translation via each node's {@code portSelectionOutput},
 * error mocks, catalog-example fetching and the {@code __mocked__} dual-write.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockNodeResultFactory - substituted result construction")
class MockNodeResultFactoryTest {

    @Mock private CatalogMockClient catalogMockClient;
    @Mock private ExecutionContext context;

    private MockNodeResultFactory factory;

    @BeforeEach
    void setUp() {
        factory = new MockNodeResultFactory();
        factory.setCatalogMockClient(catalogMockClient);
        lenient().when(context.itemIndex()).thenReturn(2);
        lenient().when(context.itemId()).thenReturn("item-2");
        lenient().when(context.tenantId()).thenReturn("tenant-1");
    }

    private StepNode mcpNode(String toolId) {
        Step step = new Step(toolId, "mcp", "Fetch Emails", null,
            Map.of("q", "{{trigger:hook.output.q}}"), null, null, null, null, null, null);
        return new StepNode("mcp:fetch_emails", step);
    }

    private NodeMock mock(Map<String, Object> raw) {
        return NodeMock.fromMap(raw, "test-node");
    }

    @Test
    @DisplayName("static mock: output returned verbatim + real-execution envelope (node_type, item identity, raw resolved_params) + __mocked__ dual-write")
    void staticMockEnvelopeParity() {
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("output", Map.of("messages", List.of(), "result_count", 0))));

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output())
            .containsEntry("result_count", 0)
            .containsEntry("node_type", "MCP")
            .containsEntry("item_index", 2)
            .containsEntry("itemIndex", 2)
            .containsEntry("item_id", "item-2")
            .containsEntry("resolved_params", Map.of("q", "{{trigger:hook.output.q}}"))
            .containsEntry(ExecutionMetadataKeys.MOCKED, true)
            .containsEntry(ExecutionMetadataKeys.MOCK_SOURCE, "static");
        assertThat(result.metadata())
            .containsEntry(ExecutionMetadataKeys.MOCKED, true)
            .containsEntry(ExecutionMetadataKeys.MOCK_SOURCE, "static");
        assertThat(ExecutionMetadataKeys.isMocked(result.output())).isTrue();
    }

    @Test
    @DisplayName("durationMs: the factory waits the simulated duration and the result's executionTimeMs reflects it")
    void durationMsSimulatesExecutionTime() {
        long before = System.currentTimeMillis();
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("output", Map.of("x", 1), "durationMs", 120)));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(elapsed).isGreaterThanOrEqualTo(120L);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(120L);
    }

    @Test
    @DisplayName("durationMs applies to error mocks too: a simulated slow failure")
    void durationMsAppliesToErrorMocks() {
        long before = System.currentTimeMillis();
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("error", Map.of("message", "boom"), "durationMs", 100)));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("no durationMs = instant substitution (no hidden wait)")
    void noDurationIsInstant() {
        long before = System.currentTimeMillis();
        factory.build(mcpNode("gmail/list"), context, mock(Map.of("output", Map.of("x", 1))));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isLessThan(1_000L);
    }

    @Test
    @DisplayName("a run cancel signal aborts the simulated duration within ~100ms (WaitNode F2.4 pattern) and still serves the mock")
    void cancelSignalAbortsWaitEarly() {
        com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher publisher =
            org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
        when(publisher.isAgentCancelSignalSet("run-1")).thenReturn(true);
        factory.setWorkflowRedisPublisher(publisher);
        lenient().when(context.runId()).thenReturn("run-1");

        long before = System.currentTimeMillis();
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("output", Map.of("x", 1), "durationMs", 8_000)));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).containsEntry("x", 1);
        assertThat(elapsed).isLessThan(4_000L); // one ~100ms slice, generous CI margin
    }

    @Test
    @DisplayName("durationMs composes with a port mock: the branch selection is intact after the wait")
    void durationComposesWithPortMock() {
        DecisionNode decision = new DecisionNode("core:check", List.of(
            new DecisionNode.ConditionalBranch("if", "x > 1", List.of()),
            new DecisionNode.ConditionalBranch("else", null, List.of())), null);

        long before = System.currentTimeMillis();
        NodeExecutionResult result = factory.build(decision, context,
            mock(Map.of("port", "if", "durationMs", 100)));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
        assertThat(result.output()).containsEntry("selected_branch_index", 0);
        assertThat(decision.getSelectedPort(result)).isEqualTo("if");
    }

    @Test
    @DisplayName("durationMs composes with catalog_example: the wait happens and the example is served")
    void durationComposesWithCatalogExample() {
        when(catalogMockClient.fetchProjectedExample(eq("gmail/list"), eq("tenant-1")))
            .thenReturn(new HashMap<>(Map.of("messages", List.of())));

        long before = System.currentTimeMillis();
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("source", "catalog_example", "durationMs", 100)));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).containsKey("messages");
    }

    @Test
    @DisplayName("an interrupt ends the simulated duration early: the mock is served immediately and the interrupt flag is restored")
    void interruptEndsWaitEarlyAndRestoresFlag() {
        Thread.currentThread().interrupt();
        try {
            long before = System.currentTimeMillis();
            NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
                mock(Map.of("output", Map.of("x", 1), "durationMs", 5_000)));
            long elapsed = System.currentTimeMillis() - before;

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(elapsed).isLessThan(5_000L);
            assertThat(Thread.currentThread().isInterrupted())
                .as("the interrupt flag must be restored for upstream cancellation handling")
                .isTrue();
        } finally {
            Thread.interrupted(); // clear the flag so it never leaks into other tests
        }
    }

    @Test
    @DisplayName("static mock output is copied: engine stamping never mutates the plan-borne map")
    void staticOutputIsCopied() {
        Map<String, Object> planOutput = Map.of("x", 1);
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("output", planOutput)));

        result.output().put("stamped", true);
        assertThat(planOutput).doesNotContainKey("stamped");
    }

    @Test
    @DisplayName("decision port mock translates to selected_branch_index and routes via getSelectedPort exactly like a real run")
    void decisionPortTranslation() {
        DecisionNode decision = new DecisionNode("core:check", List.of(
            new DecisionNode.ConditionalBranch("if", "x > 1", List.of()),
            new DecisionNode.ConditionalBranch("elsif", "x > 0", List.of()),
            new DecisionNode.ConditionalBranch("else", null, List.of())), null);

        NodeExecutionResult ifResult = factory.build(decision, context, mock(Map.of("port", "if")));
        assertThat(ifResult.output()).containsEntry("selected_branch_index", 0);
        assertThat(decision.getSelectedPort(ifResult)).isEqualTo("if");

        NodeExecutionResult elseifResult = factory.build(decision, context, mock(Map.of("port", "elseif_0")));
        assertThat(elseifResult.output()).containsEntry("selected_branch_index", 1);
        assertThat(decision.getSelectedPort(elseifResult)).isEqualTo("elseif_0");

        NodeExecutionResult elseResult = factory.build(decision, context, mock(Map.of("port", "else")));
        assertThat(decision.getSelectedPort(elseResult)).isEqualTo("else");
    }

    @Test
    @DisplayName("switch port mock translates to selected_case_index (case_N and default)")
    void switchPortTranslation() {
        SwitchNode switchNode = new SwitchNode("core:route", "{{x}}", List.of(
            new SwitchNode.SwitchCase("case", "a", "A"),
            new SwitchNode.SwitchCase("case", "b", "B"),
            new SwitchNode.SwitchCase("default", null, null)), null);

        NodeExecutionResult caseResult = factory.build(switchNode, context, mock(Map.of("port", "case_1")));
        assertThat(caseResult.output()).containsEntry("selected_case_index", 1);
        assertThat(switchNode.getSelectedPort(caseResult)).isEqualTo("case_1");

        NodeExecutionResult defaultResult = factory.build(switchNode, context, mock(Map.of("port", "default")));
        assertThat(defaultResult.output()).containsEntry("selected_case_index", 2);
        assertThat(switchNode.getSelectedPort(defaultResult)).isEqualTo("default");
    }

    @Test
    @DisplayName("classify agent port mock translates to selected_category_index + selected_category label")
    void classifyPortTranslation() {
        Agent agentConfig = new Agent(null, "classify", "Sort Email", null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            List.of(Map.of("label", "Urgent"), Map.of("label", "Normal")), null, null, null, null);
        AgentNode classify = new AgentNode("agent:sort_email", agentConfig);

        NodeExecutionResult result = factory.build(classify, context, mock(Map.of("port", "category_1")));

        assertThat(result.output())
            .containsEntry("selected_category_index", 1)
            .containsEntry("selected_category", "Normal")
            .containsEntry("node_type", "CLASSIFY");
        assertThat(classify.getSelectedPort(result)).isEqualTo("category_1");
    }

    @Test
    @DisplayName("approval-style default: port lands as selected_port with optional output merged")
    void defaultPortForm() {
        BaseNode approval = new BaseNode("core:manager_ok", NodeType.APPROVAL) {
            @Override
            public NodeExecutionResult execute(ExecutionContext ctx) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };

        NodeExecutionResult result = factory.build(approval, context,
            mock(Map.of("port", "approved", "output", Map.of("responded_by", "mock-user"))));

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.isAwaitingSignal()).as("mocked approval never yields").isFalse();
        assertThat(result.output())
            .containsEntry(ExecutionMetadataKeys.SELECTED_PORT, "approved")
            .containsEntry("responded_by", "mock-user")
            .containsEntry("node_type", "APPROVAL");
    }

    @Test
    @DisplayName("error mock produces a FAILED result with error.output + envelope + __mocked__ markers")
    void errorMockFails() {
        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("error", Map.of("message", "Rate limit exceeded", "output", Map.of("error_code", 429)))));

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorMessage()).contains("Rate limit exceeded");
        assertThat(result.output())
            .containsEntry("error_code", 429)
            .containsEntry("error", "Rate limit exceeded")
            .containsEntry(ExecutionMetadataKeys.MOCKED, true)
            .containsEntry(ExecutionMetadataKeys.MOCK_SOURCE, "error");
        assertThat(result.metadata()).containsEntry(ExecutionMetadataKeys.MOCKED, true);
    }

    @Test
    @DisplayName("catalog_example mock serves the projected example (client output) with envelope + markers")
    void catalogExampleServed() {
        Map<String, Object> clientOutput = new HashMap<>(Map.of(
            "tool_id", "gmail/list", "execution", true, "ok", true, "http_status", 200));
        when(catalogMockClient.fetchProjectedExample(eq("gmail/list"), eq("tenant-1")))
            .thenReturn(clientOutput);

        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("source", "catalog_example")));

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output())
            .containsEntry("ok", true)
            .containsEntry("http_status", 200)
            .containsEntry("node_type", "MCP")
            .containsEntry(ExecutionMetadataKeys.MOCK_SOURCE, "catalog_example");
    }

    @Test
    @DisplayName("catalog_example fetch failure = FAILED node with the explicit client message (no silent fallback)")
    void catalogExampleFailureFailsNode() {
        when(catalogMockClient.fetchProjectedExample(eq("gmail/list"), eq("tenant-1")))
            .thenThrow(new CatalogMockClient.MockExampleUnavailableException(
                "No catalog example exists for tool 'gmail/list'"));

        NodeExecutionResult result = factory.build(mcpNode("gmail/list"), context,
            mock(Map.of("source", "catalog_example")));

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorMessage().orElse("")).contains("gmail/list");
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.MOCKED, true);
    }

    @Test
    @DisplayName("catalog_example without a wired client = FAILED with a clear deployment message")
    void missingClientFailsExplicitly() {
        MockNodeResultFactory noClient = new MockNodeResultFactory();

        NodeExecutionResult result = noClient.build(mcpNode("gmail/list"), context,
            mock(Map.of("source", "catalog_example")));

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorMessage().orElse("")).contains("not available");
    }
}
