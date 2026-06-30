package com.apimarketplace.orchestrator.execution.v2.events;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionEventEmitter")
class ExecutionEventEmitterTest {

    private ExecutionEventEmitter emitter;

    @Mock
    private ExecutionNode node;

    @Mock
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        emitter = new ExecutionEventEmitter();
    }

    @Nested
    @DisplayName("emitNodeEvent()")
    class EmitNodeEventTests {
        @Test
        @DisplayName("Should emit node event without throwing")
        void shouldEmitNodeEventWithoutThrowing() {
            when(node.getNodeId()).thenReturn("mcp:step1");
            when(node.getType()).thenReturn(NodeType.MCP);
            when(context.itemId()).thenReturn("item-123");
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());

            assertDoesNotThrow(() -> emitter.emitNodeEvent(node, context, result));
        }

        @Test
        @DisplayName("Should handle different node types")
        void shouldHandleDifferentNodeTypes() {
            when(context.itemId()).thenReturn("item-123");
            NodeExecutionResult result = NodeExecutionResult.success("test-node", Map.of());

            for (NodeType type : NodeType.values()) {
                when(node.getNodeId()).thenReturn("test:" + type.name().toLowerCase());
                when(node.getType()).thenReturn(type);

                assertDoesNotThrow(() -> emitter.emitNodeEvent(node, context, result));
            }
        }

        @Test
        @DisplayName("Should handle failure result")
        void shouldHandleFailureResult() {
            when(node.getNodeId()).thenReturn("mcp:step1");
            when(node.getType()).thenReturn(NodeType.MCP);
            when(context.itemId()).thenReturn("item-123");
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "Error message");

            assertDoesNotThrow(() -> emitter.emitNodeEvent(node, context, result));
        }
    }

    @Nested
    @DisplayName("emitStepEvent()")
    class EmitStepEventTests {
        @Test
        @DisplayName("Should emit step event without throwing")
        void shouldEmitStepEventWithoutThrowing() {
            when(context.itemId()).thenReturn("item-123");
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());
            Map<String, Integer> metrics = Map.of("success", 5, "failure", 1);

            assertDoesNotThrow(() -> emitter.emitStepEvent("mcp:step1", context, result, metrics));
        }

        @Test
        @DisplayName("Should handle empty metrics")
        void shouldHandleEmptyMetrics() {
            when(context.itemId()).thenReturn("item-123");
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());

            assertDoesNotThrow(() -> emitter.emitStepEvent("mcp:step1", context, result, Map.of()));
        }
    }

    @Nested
    @DisplayName("emitEdgeEvent()")
    class EmitEdgeEventTests {
        @Test
        @DisplayName("Should emit edge event without throwing")
        void shouldEmitEdgeEventWithoutThrowing() {
            assertDoesNotThrow(() ->
                emitter.emitEdgeEvent("run-123", "mcp:step1", "mcp:step2", "RUNNING")
            );
        }

        @Test
        @DisplayName("Should handle different statuses")
        void shouldHandleDifferentStatuses() {
            String[] statuses = {"PENDING", "RUNNING", "COMPLETED", "SKIPPED", "FAILED"};

            for (String status : statuses) {
                assertDoesNotThrow(() ->
                    emitter.emitEdgeEvent("run-123", "mcp:from", "mcp:to", status)
                );
            }
        }
    }

    @Nested
    @DisplayName("emitWorkflowCompletionEvent()")
    class EmitWorkflowCompletionEventTests {
        @Test
        @DisplayName("Should emit completion event without throwing")
        void shouldEmitCompletionEventWithoutThrowing() {
            assertDoesNotThrow(() ->
                emitter.emitWorkflowCompletionEvent("run-123", "COMPLETED", 10, 8, 2)
            );
        }

        @Test
        @DisplayName("Should handle zero counts")
        void shouldHandleZeroCounts() {
            assertDoesNotThrow(() ->
                emitter.emitWorkflowCompletionEvent("run-123", "COMPLETED", 0, 0, 0)
            );
        }

        @Test
        @DisplayName("Should handle failure status")
        void shouldHandleFailureStatus() {
            assertDoesNotThrow(() ->
                emitter.emitWorkflowCompletionEvent("run-123", "FAILED", 10, 5, 5)
            );
        }
    }

    @Nested
    @DisplayName("emitMergeEvent()")
    class EmitMergeEventTests {
        @Test
        @DisplayName("Should emit merge event without throwing")
        void shouldEmitMergeEventWithoutThrowing() {
            Map<String, Integer> sourceItemCounts = Map.of(
                "branch_a", 5,
                "branch_b", 3
            );

            assertDoesNotThrow(() ->
                emitter.emitMergeEvent("core:merge1", context, sourceItemCounts)
            );
        }

        @Test
        @DisplayName("Should handle empty source counts")
        void shouldHandleEmptySourceCounts() {
            assertDoesNotThrow(() ->
                emitter.emitMergeEvent("core:merge1", context, Map.of())
            );
        }

        @Test
        @DisplayName("Should handle many sources")
        void shouldHandleManySources() {
            Map<String, Integer> sourceItemCounts = Map.of(
                "branch_a", 5,
                "branch_b", 3,
                "branch_c", 7,
                "branch_d", 2
            );

            assertDoesNotThrow(() ->
                emitter.emitMergeEvent("core:merge1", context, sourceItemCounts)
            );
        }
    }

    @Nested
    @DisplayName("General behavior")
    class GeneralBehaviorTests {
        @Test
        @DisplayName("Should be thread-safe")
        void shouldBeThreadSafe() throws InterruptedException {
            when(node.getNodeId()).thenReturn("mcp:step1");
            when(node.getType()).thenReturn(NodeType.MCP);
            when(context.itemId()).thenReturn("item-123");

            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];

            for (int i = 0; i < numThreads; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < 100; j++) {
                        NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());
                        emitter.emitNodeEvent(node, context, result);
                        emitter.emitEdgeEvent("run-1", "mcp:a", "mcp:b", "RUNNING");
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }

            // No exceptions should have been thrown
        }
    }
}
