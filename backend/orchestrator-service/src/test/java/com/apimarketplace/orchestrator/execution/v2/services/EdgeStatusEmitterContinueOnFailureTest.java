package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outgoing edges of a node that failed with {@code continueOnFailure}.
 *
 * <p>Its successors run, so its edges are TRAVERSED: marking them SKIPPED would draw a skipped edge
 * into a node that then runs. This emitter is the single writer of a failed node's direct edges
 * (a second writer double-counts, prod run_<id>), so a continued edge gets
 * COMPLETED here and never also SKIPPED. An unflagged failure keeps the legacy SKIPPED edges.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EdgeStatusEmitter - edges of a continueOnFailure node")
class EdgeStatusEmitterContinueOnFailureTest {

    @Mock private EdgeStatusService edgeStatusService;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private WorkflowExecution execution;

    private EdgeStatusEmitter emitter;
    private BaseNode node;

    @BeforeEach
    void setUp() {
        emitter = new EdgeStatusEmitter(edgeStatusService, skipPropagationService);
        BaseNode after = mock(BaseNode.class);
        when(after.getNodeId()).thenReturn("mcp:after");
        BaseNode join = mock(BaseNode.class);
        when(join.getNodeId()).thenReturn("core:join");
        node = mock(BaseNode.class);
        when(node.getNodeId()).thenReturn("mcp:call");
        when(node.getType()).thenReturn(NodeType.MCP);
        when(node.getNextNodes(any())).thenReturn(List.of());
        when(node.getSuccessors()).thenReturn(List.of(after, join));
    }

    private static NodeExecutionResult failure(Map<String, Object> output, Map<String, Object> metadata) {
        return new NodeExecutionResult("mcp:call", NodeStatus.FAILED, output,
            Optional.of("provider down"), metadata, 5L);
    }

    @Test
    @DisplayName("REGRESSION: a continued failure marks every outgoing edge RUNNING then COMPLETED, never SKIPPED")
    void continuedFailureTraversesItsEdges() {
        NodeExecutionResult result = failure(Map.of(),
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true));

        emitter.emitOutgoingEdges(execution, node, 2, 1, result, false, 3, "trigger:start");

        InOrder order = inOrder(edgeStatusService);
        order.verify(edgeStatusService).markEdgeRunning(execution, "mcp:call", "mcp:after", 2, 1);
        order.verify(edgeStatusService).markEdgeCompleted(execution, "mcp:call", "mcp:after", 2, 1);
        verify(edgeStatusService).markEdgeRunning(execution, "mcp:call", "core:join", 2, 1);
        verify(edgeStatusService).markEdgeCompleted(execution, "mcp:call", "core:join", 2, 1);
        verify(edgeStatusService, never()).markEdgeSkipped(any(), anyString(), anyString(), anyInt(), any());
        verify(skipPropagationService, never()).persistAndPropagateSkip(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("the flag is read from the output too (a persisted result carries it there)")
    void flagInOutputIsEnough() {
        NodeExecutionResult result = failure(
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true), Map.of());

        emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 1, "trigger:start");

        verify(edgeStatusService).markEdgeCompleted(execution, "mcp:call", "mcp:after", 0, null);
        verify(edgeStatusService, never()).markEdgeSkipped(any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an unflagged failure keeps the legacy SKIPPED edges and marks nothing COMPLETED")
    void unflaggedFailureSkipsItsEdges() {
        NodeExecutionResult result = failure(Map.of(), Map.of());

        emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 1, "trigger:start");

        verify(edgeStatusService).markEdgeSkipped(execution, "mcp:call", "mcp:after", 0, null);
        verify(edgeStatusService).markEdgeSkipped(execution, "mcp:call", "core:join", 0, null);
        verify(edgeStatusService, never()).markEdgeCompleted(any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("the flag on a success does not change the success path (routing by getNextNodes)")
    void flagOnSuccessIsInert() {
        NodeExecutionResult result = new NodeExecutionResult("mcp:call", NodeStatus.COMPLETED,
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true), Optional.empty(), Map.of(), 5L);

        emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 1, "trigger:start");

        // getNextNodes returns nothing for this node, so a success marks no edge at all.
        verify(edgeStatusService, never()).markEdgeCompleted(any(), anyString(), anyString(), anyInt(), any());
        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
    }
}
