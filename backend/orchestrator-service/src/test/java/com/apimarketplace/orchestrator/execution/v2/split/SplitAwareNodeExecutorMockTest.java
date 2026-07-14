package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.MockExecutionService;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Wiring tests for the two mock-mode interception points of
 * {@link SplitAwareNodeExecutor}: the non-fan-out leaf ({@code executeNodeBody})
 * and the per-item fan-out invoker. When the mock service substitutes a result,
 * {@code node.execute} must never run; when it returns null (or is not wired),
 * behavior is byte-identical to before.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAwareNodeExecutor - per-node mock interception")
class SplitAwareNodeExecutorMockTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "mcp:step1";
    private static final String SPLIT_KEY = "core:split1";

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private ExecutionContext context;
    @Mock private MockExecutionService mockExecutionService;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager, nodeCompletionService,
            null, null, null, null,
            Executors.newFixedThreadPool(2));
        nodeMap = new HashMap<>();
        nodeMap.put(SPLIT_KEY, new TestNode(SPLIT_KEY, NodeType.SPLIT));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private static NodeExecutionResult mockedResult() {
        Map<String, Object> output = new HashMap<>();
        output.put("result_count", 0);
        output.put(ExecutionMetadataKeys.MOCKED, true);
        return NodeExecutionResult.success(NODE_ID, output);
    }

    @Test
    @DisplayName("non-fan-out path: a substituted mock short-circuits node.execute entirely")
    void nonFanOutSubstitution() {
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.empty());
        executor.setMockExecutionService(mockExecutionService);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        when(mockExecutionService.tryMock(node, context)).thenReturn(mockedResult());

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap);

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(ExecutionMetadataKeys.isMocked(result.output())).isTrue();
        assertThat(node.getExecuteCount()).as("node.execute never called when mocked").isZero();
    }

    @Test
    @DisplayName("REGRESSION (byte-identical passthrough): tryMock=null and unwired service both execute the real node body")
    void passthroughWhenNotMocked() {
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.empty());
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);

        // Service wired but declines
        executor.setMockExecutionService(mockExecutionService);
        when(mockExecutionService.tryMock(node, context)).thenReturn(null);
        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap);
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(node.getExecuteCount()).isEqualTo(1);

        // Service not wired at all (unit-test construction default)
        executor.setMockExecutionService(null);
        executor.execute(node, context, RUN_ID, nodeMap);
        assertThat(node.getExecuteCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("split fan-out: every item is substituted (node.execute never runs) and item_index is stamped per item")
    void perItemSubstitution() {
        SplitContext splitContext = SplitContext.create(SPLIT_KEY + ":0", List.of("a", "b", "c"));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.of(splitContext));
        when(context.withGlobalData(any(), any())).thenReturn(context);
        when(context.withItemIndex(anyInt())).thenReturn(context);
        lenient().when(context.state())
            .thenReturn(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create());

        executor.setMockExecutionService(mockExecutionService);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        AtomicInteger mockCalls = new AtomicInteger();
        when(mockExecutionService.tryMock(eq(node), any())).thenAnswer(inv -> {
            mockCalls.incrementAndGet();
            return mockedResult();
        });

        NodeExecutionResult summary = executor.execute(node, context, RUN_ID, nodeMap);

        assertThat(summary.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(mockCalls.get()).as("one mock consult per item").isEqualTo(3);
        assertThat(node.getExecuteCount()).as("real body never runs").isZero();
        assertThat(summary.output()).containsEntry(ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 3);
    }

    // =====================================================================
    // Test node (mirrors SplitAwareNodeExecutorNodePolicyTest.TestNode)
    // =====================================================================

    private static class TestNode extends BaseNode {
        private final AtomicInteger executeCount = new AtomicInteger(0);

        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        int getExecuteCount() {
            return executeCount.get();
        }

        @Override
        public boolean skipsSplitHandling() {
            return type == NodeType.SPLIT || type == NodeType.MERGE
                || type == NodeType.DECISION || type == NodeType.FORK
                || type == NodeType.LOOP || type == NodeType.TRIGGER
                || type == NodeType.SWITCH || type == NodeType.END;
        }

        @Override
        public boolean isSplitNode() {
            return type == NodeType.SPLIT;
        }

        @Override
        public boolean isMergeNode() {
            return type == NodeType.MERGE;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            executeCount.incrementAndGet();
            return NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}
