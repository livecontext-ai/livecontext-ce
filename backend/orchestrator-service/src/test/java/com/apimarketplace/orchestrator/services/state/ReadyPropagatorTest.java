package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadyPropagator.
 * Tests ready state propagation for workflow nodes.
 */
@ExtendWith(MockitoExtension.class)
class ReadyPropagatorTest {

    @Mock
    private WorkflowGraph graph;

    @Mock
    private ReadyPropagator.StatusProvider statusProvider;

    @Mock
    private ReadyPropagator.StatusUpdater statusUpdater;

    @Mock
    private ReadyPropagator.DecisionBranchProvider decisionBranchProvider;

    private ReadyPropagator propagator;

    @BeforeEach
    void setUp() {
        propagator = new ReadyPropagator(graph, statusProvider, statusUpdater, decisionBranchProvider);
    }

    // ========================================================================
    // checkIfReady() Tests
    // ========================================================================

    @Nested
    @DisplayName("checkIfReady()")
    class CheckIfReadyTests {

        @Test
        @DisplayName("Should return STAY_PENDING for trigger node")
        void shouldReturnStayPendingForTrigger() {
            // Given
            NodeId triggerId = NodeId.trigger("start");
            WorkflowNode triggerNode = WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER).build();

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(triggerNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return MAKE_READY when all predecessors are COMPLETED for MCP node")
        void shouldReturnMakeReadyWhenAllPredecessorsCompleted() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId stepId = NodeId.step("api_call");
            WorkflowNode stepNode = WorkflowNode.builder(stepId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(stepNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when predecessors not yet completed")
        void shouldReturnStayPendingWhenPredecessorsNotCompleted() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId stepId = NodeId.step("api_call");
            WorkflowNode stepNode = WorkflowNode.builder(stepId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.PENDING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(stepNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when all predecessors are SKIPPED")
        void shouldReturnMakeSkippedWhenAllPredecessorsSkipped() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId stepId = NodeId.step("api_call");
            WorkflowNode stepNode = WorkflowNode.builder(stepId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.SKIPPED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(stepNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING for node with no predecessors")
        void shouldReturnStayPendingForNodeWithNoPredecessors() {
            // Given
            NodeId stepId = NodeId.step("orphan");
            WorkflowNode stepNode = WorkflowNode.builder(stepId, WorkflowNode.NodeType.MCP).build();

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(stepNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return MAKE_READY for AGENT node when predecessors completed")
        void shouldReturnMakeReadyForAgentNode() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId agentId = NodeId.agent("assistant");
            WorkflowNode agentNode = WorkflowNode.builder(agentId, WorkflowNode.NodeType.AGENT)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(agentNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }
    }

    // ========================================================================
    // checkMergeNodeReady() Tests - via checkIfReady()
    // ========================================================================

    @Nested
    @DisplayName("checkMergeNodeReady() - Merge Node Logic")
    class CheckMergeNodeReadyTests {

        @Test
        @DisplayName("Should return MAKE_READY when all predecessors resolved and at least one COMPLETED")
        void shouldReturnMakeReadyWhenAllResolvedAndOneCompleted() {
            // Given: Merge node with 2 predecessors
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.COMPLETED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return MAKE_READY when one COMPLETED and one SKIPPED (SKIPPED counts as resolved)")
        void shouldReturnMakeReadyWhenOneCompletedOneSkipped() {
            // Given
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when ALL predecessors are SKIPPED")
        void shouldReturnMakeSkippedWhenAllPredecessorsSkipped() {
            // Given
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when some predecessors still pending")
        void shouldReturnStayPendingWhenSomePredecessorsPending() {
            // Given
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.PENDING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when some predecessors still RUNNING")
        void shouldReturnStayPendingWhenSomePredecessorsRunning() {
            // Given
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.RUNNING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }
    }

    // ========================================================================
    // checkDecisionReady() Tests - via checkIfReady()
    // ========================================================================

    @Nested
    @DisplayName("checkDecisionReady() - Decision Node Logic")
    class CheckDecisionReadyTests {

        @Test
        @DisplayName("Should return MAKE_READY when predecessor is COMPLETED")
        void shouldReturnMakeReadyWhenPredecessorCompleted() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(decisionNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when predecessor is SKIPPED")
        void shouldReturnMakeSkippedWhenPredecessorSkipped() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.SKIPPED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(decisionNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when predecessor is PENDING")
        void shouldReturnStayPendingWhenPredecessorPending() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.PENDING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(decisionNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when decision has no predecessors")
        void shouldReturnStayPendingWhenNoPredecessors() {
            // Given
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .build();

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(decisionNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }
    }

    // ========================================================================
    // checkForkReady() Tests - via checkIfReady()
    // ========================================================================

    @Nested
    @DisplayName("checkForkReady() - Fork Node Logic")
    class CheckForkReadyTests {

        @Test
        @DisplayName("Should return MAKE_READY when predecessor is COMPLETED")
        void shouldReturnMakeReadyWhenPredecessorCompleted() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId forkId = new NodeId("fork", "parallel");
            WorkflowNode forkNode = WorkflowNode.builder(forkId, WorkflowNode.NodeType.FORK)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(forkNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when all predecessors are SKIPPED")
        void shouldReturnMakeSkippedWhenAllPredecessorsSkipped() {
            // Given
            NodeId pred1 = NodeId.step("step1");
            NodeId pred2 = NodeId.step("step2");
            NodeId forkId = new NodeId("fork", "parallel");
            WorkflowNode forkNode = WorkflowNode.builder(forkId, WorkflowNode.NodeType.FORK)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(forkNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when fork has no predecessors")
        void shouldReturnStayPendingWhenNoPredecessors() {
            // Given
            NodeId forkId = new NodeId("fork", "parallel");
            WorkflowNode forkNode = WorkflowNode.builder(forkId, WorkflowNode.NodeType.FORK)
                    .build();

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(forkNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }
    }

    // ========================================================================
    // recalculateReadyState() Tests
    // ========================================================================

    @Nested
    @DisplayName("recalculateReadyState()")
    class RecalculateReadyStateTests {

        @Test
        @DisplayName("Should not recalculate if node is not PENDING")
        void shouldNotRecalculateIfNotPending() {
            // Given
            NodeId nodeId = NodeId.step("step1");
            when(statusProvider.getStatus(nodeId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.recalculateReadyState(nodeId);

            // Then
            verify(statusUpdater, never()).transitionTo(any(), any());
            verify(statusUpdater, never()).markSkipped(any());
        }

        @Test
        @DisplayName("Should transition to READY when checkIfReady returns MAKE_READY")
        void shouldTransitionToReadyWhenMakeReady() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId nodeId = NodeId.step("step1");
            WorkflowNode node = WorkflowNode.builder(nodeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(nodeId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(nodeId)).thenReturn(node);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.recalculateReadyState(nodeId);

            // Then
            verify(statusUpdater).transitionTo(nodeId, NodeStatus.READY);
        }

        @Test
        @DisplayName("Should mark skipped when checkIfReady returns MAKE_SKIPPED")
        void shouldMarkSkippedWhenMakeSkipped() {
            // Given
            NodeId predId = NodeId.trigger("start");
            NodeId nodeId = NodeId.step("step1");
            WorkflowNode node = WorkflowNode.builder(nodeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(nodeId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(nodeId)).thenReturn(node);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.recalculateReadyState(nodeId);

            // Then
            verify(statusUpdater).markSkipped(nodeId);
        }
    }

    // ========================================================================
    // propagateCompletionToSuccessors() Tests
    // ========================================================================

    @Nested
    @DisplayName("propagateCompletionToSuccessors()")
    class PropagateCompletionToSuccessorsTests {

        @Test
        @DisplayName("Should recalculate ready state for non-decision successors")
        void shouldRecalculateReadyStateForNonDecisionSuccessors() {
            // Given
            NodeId completedId = NodeId.trigger("start");
            NodeId successorId = NodeId.step("step1");
            WorkflowNode completedNode = WorkflowNode.builder(completedId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(completedId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(completedId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateCompletionToSuccessors(completedNode);

            // Then
            verify(statusUpdater).transitionTo(successorId, NodeStatus.READY);
        }

        @Test
        @DisplayName("Should handle decision successor specially")
        void shouldHandleDecisionSuccessorSpecially() {
            // Given
            NodeId completedId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode completedNode = WorkflowNode.builder(completedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(decisionId)
                    .build();
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(completedId)
                    .build();

            when(graph.getNodeOrNull(decisionId)).thenReturn(decisionNode);
            when(statusProvider.getStatus(decisionId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(decisionId)).thenReturn(decisionNode);
            when(statusProvider.getStatus(completedId)).thenReturn(NodeStatus.COMPLETED);
            when(decisionBranchProvider.getDecisionBranches(decisionId)).thenReturn(null);

            // When
            propagator.propagateCompletionToSuccessors(completedNode);

            // Then - Decision should transition to READY then COMPLETED
            verify(statusUpdater).transitionTo(decisionId, NodeStatus.READY);
            verify(statusUpdater).transitionTo(decisionId, NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should skip null successors gracefully")
        void shouldSkipNullSuccessorsGracefully() {
            // Given
            NodeId completedId = NodeId.trigger("start");
            NodeId successorId = NodeId.step("step1");
            WorkflowNode completedNode = WorkflowNode.builder(completedId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(successorId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(null);

            // When / Then - Should not throw
            assertDoesNotThrow(() -> propagator.propagateCompletionToSuccessors(completedNode));
        }
    }

    // ========================================================================
    // handleDecisionBecameReady() Tests
    // ========================================================================

    @Nested
    @DisplayName("handleDecisionBecameReady()")
    class HandleDecisionBecameReadyTests {

        @Test
        @DisplayName("Should not process if decision is not PENDING")
        void shouldNotProcessIfNotPending() {
            // Given
            NodeId decisionId = NodeId.decision("check");
            when(statusProvider.getStatus(decisionId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.handleDecisionBecameReady(decisionId);

            // Then
            verify(statusUpdater, never()).transitionTo(any(), any());
        }

        @Test
        @DisplayName("Should mark decision as skipped if predecessor is skipped")
        void shouldMarkDecisionSkippedIfPredecessorSkipped() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(decisionId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(decisionId)).thenReturn(decisionNode);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.handleDecisionBecameReady(decisionId);

            // Then
            verify(statusUpdater).markSkipped(decisionId);
            verify(statusUpdater, never()).transitionTo(any(), eq(NodeStatus.READY));
        }

        @Test
        @DisplayName("Should use saved branch selection when available")
        void shouldUseSavedBranchSelectionWhenAvailable() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId decisionId = NodeId.decision("check");
            NodeId ifTarget = NodeId.step("if_branch");
            NodeId elseTarget = NodeId.step("else_branch");

            Map<String, NodeId> portSuccessors = new HashMap<>();
            portSuccessors.put("if", ifTarget);
            portSuccessors.put("else", elseTarget);

            WorkflowNode decisionNode = WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(predId)
                    .addSuccessor(ifTarget)
                    .addSuccessor(elseTarget)
                    .portSuccessors(portSuccessors)
                    .build();

            when(statusProvider.getStatus(decisionId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(decisionId)).thenReturn(decisionNode);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);
            when(decisionBranchProvider.getDecisionBranches(decisionId)).thenReturn(Set.of("if"));
            when(statusProvider.getStatus(elseTarget)).thenReturn(NodeStatus.PENDING);

            // When
            propagator.handleDecisionBecameReady(decisionId);

            // Then
            verify(statusUpdater).transitionTo(decisionId, NodeStatus.READY);
            verify(statusUpdater).transitionTo(decisionId, NodeStatus.COMPLETED);
            verify(decisionBranchProvider).recalculateReadyState(ifTarget);
            verify(statusUpdater).markSkipped(elseTarget);
        }
    }

    // ========================================================================
    // handleForkBecameReady() Tests
    // ========================================================================

    @Nested
    @DisplayName("handleForkBecameReady()")
    class HandleForkBecameReadyTests {

        @Test
        @DisplayName("Should not process if fork is not PENDING")
        void shouldNotProcessIfNotPending() {
            // Given
            NodeId forkId = new NodeId("fork", "parallel");
            when(statusProvider.getStatus(forkId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.handleForkBecameReady(forkId);

            // Then
            verify(statusUpdater, never()).transitionTo(any(), any());
        }

        @Test
        @DisplayName("Should activate ALL successors when fork becomes ready")
        void shouldActivateAllSuccessorsWhenForkBecomesReady() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId forkId = new NodeId("fork", "parallel");
            NodeId branch1 = NodeId.step("branch_a");
            NodeId branch2 = NodeId.step("branch_b");

            WorkflowNode forkNode = WorkflowNode.builder(forkId, WorkflowNode.NodeType.FORK)
                    .addPredecessor(predId)
                    .addSuccessor(branch1)
                    .addSuccessor(branch2)
                    .build();

            when(statusProvider.getStatus(forkId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(forkId)).thenReturn(forkNode);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.handleForkBecameReady(forkId);

            // Then
            verify(statusUpdater).transitionTo(forkId, NodeStatus.READY);
            verify(statusUpdater).transitionTo(forkId, NodeStatus.COMPLETED);
            verify(decisionBranchProvider).recalculateReadyState(branch1);
            verify(decisionBranchProvider).recalculateReadyState(branch2);
        }

        @Test
        @DisplayName("Should mark fork as skipped if all predecessors skipped")
        void shouldMarkForkSkippedIfAllPredecessorsSkipped() {
            // Given
            NodeId predId = NodeId.step("step1");
            NodeId forkId = new NodeId("fork", "parallel");

            WorkflowNode forkNode = WorkflowNode.builder(forkId, WorkflowNode.NodeType.FORK)
                    .addPredecessor(predId)
                    .build();

            when(statusProvider.getStatus(forkId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(forkId)).thenReturn(forkNode);
            when(statusProvider.getStatus(predId)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.handleForkBecameReady(forkId);

            // Then
            verify(statusUpdater).markSkipped(forkId);
            verify(statusUpdater, never()).transitionTo(any(), eq(NodeStatus.READY));
        }
    }

    // ========================================================================
    // propagateSkipToSuccessors() Tests
    // ========================================================================

    @Nested
    @DisplayName("propagateSkipToSuccessors()")
    class PropagateSkipToSuccessorsTests {

        @Test
        @DisplayName("Should skip successor when it should be skipped")
        void shouldSkipSuccessorWhenShouldBeSkipped() {
            // Given
            NodeId skippedId = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");
            WorkflowNode skippedNode = WorkflowNode.builder(skippedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedId)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then
            verify(statusUpdater).markSkipped(successorId);
        }

        @Test
        @DisplayName("Should not skip merge node unless ALL predecessors are skipped")
        void shouldNotSkipMergeNodeUnlessAllPredecessorsSkipped() {
            // Given
            NodeId skippedId = NodeId.step("step1");
            NodeId completedId = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge");
            WorkflowNode skippedNode = WorkflowNode.builder(skippedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(mergeId)
                    .build();
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedId)
                    .addPredecessor(completedId)
                    .build();

            when(graph.getNodeOrNull(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(mergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedId)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(completedId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then - Merge should NOT be skipped because one predecessor is COMPLETED
            verify(statusUpdater, never()).markSkipped(mergeId);
        }

        @Test
        @DisplayName("Should skip merge node when ALL predecessors are skipped")
        void shouldSkipMergeNodeWhenAllPredecessorsSkipped() {
            // Given
            NodeId skippedId1 = NodeId.step("step1");
            NodeId skippedId2 = NodeId.step("step2");
            NodeId mergeId = NodeId.step("merge");
            WorkflowNode skippedNode = WorkflowNode.builder(skippedId1, WorkflowNode.NodeType.MCP)
                    .addSuccessor(mergeId)
                    .build();
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedId1)
                    .addPredecessor(skippedId2)
                    .build();

            when(graph.getNodeOrNull(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(mergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedId1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(skippedId2)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then - Merge SHOULD be skipped because all predecessors are skipped
            verify(statusUpdater).markSkipped(mergeId);
        }

        @Test
        @DisplayName("Should not skip already terminal successor")
        void shouldNotSkipAlreadyTerminalSuccessor() {
            // Given
            NodeId skippedId = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");
            WorkflowNode skippedNode = WorkflowNode.builder(skippedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then - Already COMPLETED, should not be skipped
            verify(statusUpdater, never()).markSkipped(successorId);
        }
    }

    // ========================================================================
    // recalculateAllPendingNodes() Tests
    // ========================================================================

    @Nested
    @DisplayName("recalculateAllPendingNodes()")
    class RecalculateAllPendingNodesTests {

        @Test
        @DisplayName("Should recalculate all pending nodes in topological order")
        void shouldRecalculateAllPendingNodesInOrder() {
            // Given
            NodeId triggerId = NodeId.trigger("start");
            NodeId step1Id = NodeId.step("step1");
            NodeId step2Id = NodeId.step("step2");

            WorkflowNode step1Node = WorkflowNode.builder(step1Id, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId)
                    .build();
            WorkflowNode step2Node = WorkflowNode.builder(step2Id, WorkflowNode.NodeType.MCP)
                    .addPredecessor(step1Id)
                    .build();

            when(graph.topologicalSort()).thenReturn(List.of(triggerId, step1Id, step2Id));
            when(statusProvider.getStatus(triggerId)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(step1Id)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(step2Id)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(step1Id)).thenReturn(step1Node);
            when(graph.getNode(step2Id)).thenReturn(step2Node);

            // When
            propagator.recalculateAllPendingNodes();

            // Then
            verify(statusUpdater).transitionTo(step1Id, NodeStatus.READY);
        }

        @Test
        @DisplayName("Should handle cyclic graphs gracefully")
        void shouldHandleCyclicGraphsGracefully() {
            // Given - Graph with cycle (loop)
            NodeId triggerId = NodeId.trigger("start");
            NodeId loopId = NodeId.loop("while_loop");

            when(graph.topologicalSort()).thenThrow(new IllegalStateException("Graph contains a cycle"));
            when(graph.getAllNodeIds()).thenReturn(java.util.Set.of(triggerId, loopId));
            when(statusProvider.getStatus(triggerId)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(loopId)).thenReturn(NodeStatus.COMPLETED);

            // When / Then - Should not throw
            assertDoesNotThrow(() -> propagator.recalculateAllPendingNodes());
        }
    }

    // ========================================================================
    // shouldSkipSuccessor() - Implicit Merge Tests
    // ========================================================================

    @Nested
    @DisplayName("shouldSkipSuccessor() - Implicit Merge Handling")
    class ShouldSkipSuccessorImplicitMergeTests {

        @Test
        @DisplayName("Should NOT skip implicit merge node when only one predecessor is SKIPPED and another is COMPLETED")
        void shouldNotSkipImplicitMergeWhenOnlyOnePredecessorSkipped() {
            // Given: An MCP node (not explicit MERGE type) with 2 predecessors (implicit merge)
            // This simulates a convergence point after a decision: both branches lead to the same node
            NodeId skippedPred = NodeId.step("branch_a");
            NodeId completedPred = NodeId.step("branch_b");
            NodeId implicitMergeId = NodeId.step("converge_point");

            WorkflowNode skippedNode = WorkflowNode.builder(skippedPred, WorkflowNode.NodeType.MCP)
                    .addSuccessor(implicitMergeId)
                    .build();
            WorkflowNode implicitMergeNode = WorkflowNode.builder(implicitMergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedPred)
                    .addPredecessor(completedPred)
                    .build();

            when(graph.getNodeOrNull(implicitMergeId)).thenReturn(implicitMergeNode);
            when(statusProvider.getStatus(implicitMergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedPred)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(completedPred)).thenReturn(NodeStatus.COMPLETED);

            // When: propagateSkipToSuccessors is called for the skipped predecessor
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: The implicit merge node should NOT be skipped because branch_b is still COMPLETED
            verify(statusUpdater, never()).markSkipped(implicitMergeId);
        }

        @Test
        @DisplayName("Should skip implicit merge node when ALL predecessors are SKIPPED")
        void shouldSkipImplicitMergeWhenAllPredecessorsSkipped() {
            // Given: An MCP node with 2 predecessors, both SKIPPED
            NodeId skippedPred1 = NodeId.step("branch_a");
            NodeId skippedPred2 = NodeId.step("branch_b");
            NodeId implicitMergeId = NodeId.step("converge_point");

            WorkflowNode skippedNode = WorkflowNode.builder(skippedPred1, WorkflowNode.NodeType.MCP)
                    .addSuccessor(implicitMergeId)
                    .build();
            WorkflowNode implicitMergeNode = WorkflowNode.builder(implicitMergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedPred1)
                    .addPredecessor(skippedPred2)
                    .build();

            when(graph.getNodeOrNull(implicitMergeId)).thenReturn(implicitMergeNode);
            when(statusProvider.getStatus(implicitMergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedPred1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(skippedPred2)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: The implicit merge node SHOULD be skipped because ALL predecessors are SKIPPED
            verify(statusUpdater).markSkipped(implicitMergeId);
        }

        @Test
        @DisplayName("Should NOT skip implicit merge node when one predecessor is PENDING")
        void shouldNotSkipImplicitMergeWhenOnePredecessorPending() {
            // Given: An MCP node with 2 predecessors, one SKIPPED and one still PENDING
            NodeId skippedPred = NodeId.step("branch_a");
            NodeId pendingPred = NodeId.step("branch_b");
            NodeId implicitMergeId = NodeId.step("converge_point");

            WorkflowNode skippedNode = WorkflowNode.builder(skippedPred, WorkflowNode.NodeType.MCP)
                    .addSuccessor(implicitMergeId)
                    .build();
            WorkflowNode implicitMergeNode = WorkflowNode.builder(implicitMergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedPred)
                    .addPredecessor(pendingPred)
                    .build();

            when(graph.getNodeOrNull(implicitMergeId)).thenReturn(implicitMergeNode);
            when(statusProvider.getStatus(implicitMergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedPred)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pendingPred)).thenReturn(NodeStatus.PENDING);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: The implicit merge node should NOT be skipped - the pending predecessor might still complete
            verify(statusUpdater, never()).markSkipped(implicitMergeId);
        }

        @Test
        @DisplayName("Should still skip single-predecessor node when predecessor is SKIPPED")
        void shouldStillSkipSinglePredecessorNodeWhenPredecessorSkipped() {
            // Given: A node with only 1 predecessor (NOT an implicit merge)
            NodeId skippedPred = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");

            WorkflowNode skippedNode = WorkflowNode.builder(skippedPred, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(skippedPred)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedPred)).thenReturn(NodeStatus.SKIPPED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: Single-predecessor nodes should still be skipped when their only predecessor is SKIPPED
            verify(statusUpdater).markSkipped(successorId);
        }

        @Test
        @DisplayName("Should NOT skip implicit merge AGENT node when only one predecessor is SKIPPED")
        void shouldNotSkipImplicitMergeAgentNodeWhenOnlyOnePredecessorSkipped() {
            // Given: An AGENT node with 2 predecessors (implicit merge)
            NodeId skippedPred = NodeId.step("branch_a");
            NodeId completedPred = NodeId.step("branch_b");
            NodeId agentMergeId = NodeId.agent("assistant");

            WorkflowNode skippedNode = WorkflowNode.builder(skippedPred, WorkflowNode.NodeType.MCP)
                    .addSuccessor(agentMergeId)
                    .build();
            WorkflowNode agentMergeNode = WorkflowNode.builder(agentMergeId, WorkflowNode.NodeType.AGENT)
                    .addPredecessor(skippedPred)
                    .addPredecessor(completedPred)
                    .build();

            when(graph.getNodeOrNull(agentMergeId)).thenReturn(agentMergeNode);
            when(statusProvider.getStatus(agentMergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(skippedPred)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(completedPred)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: The agent implicit merge should NOT be skipped
            verify(statusUpdater, never()).markSkipped(agentMergeId);
        }

        @Test
        @DisplayName("Should NOT skip implicit merge with 3 predecessors when only some are SKIPPED")
        void shouldNotSkipImplicitMergeWithThreePredecessorsWhenOnlySomeSkipped() {
            // Given: A node with 3 predecessors, 2 SKIPPED and 1 COMPLETED
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId pred3 = NodeId.step("branch_c");
            NodeId mergeId = NodeId.step("converge");

            WorkflowNode skippedNode = WorkflowNode.builder(pred1, WorkflowNode.NodeType.MCP)
                    .addSuccessor(mergeId)
                    .build();
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .addPredecessor(pred3)
                    .build();

            when(graph.getNodeOrNull(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(mergeId)).thenReturn(NodeStatus.PENDING);
            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred3)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateSkipToSuccessors(skippedNode);

            // Then: Should NOT be skipped because pred3 is COMPLETED
            verify(statusUpdater, never()).markSkipped(mergeId);
        }
    }

    // ========================================================================
    // FAILED Predecessor Tests - Merge Node Deadlock Fix
    // ========================================================================

    @Nested
    @DisplayName("FAILED Predecessor Handling - Merge Node Deadlock Fix")
    class FailedPredecessorMergeTests {

        @Test
        @DisplayName("Should return MAKE_READY when one predecessor COMPLETED and one FAILED")
        void shouldReturnMakeReadyWhenOneCompletedOneFailed() {
            // Given: Merge node with 2 predecessors - one branch succeeded, one failed
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: Merge should become READY because all predecessors are resolved
            // and at least one is COMPLETED
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when ALL predecessors are FAILED")
        void shouldReturnMakeSkippedWhenAllPredecessorsFailed() {
            // Given: Merge node with 2 predecessors - both branches failed
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: Merge should be SKIPPED because no branch completed successfully
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return MAKE_SKIPPED when predecessors are mix of FAILED and SKIPPED but none COMPLETED")
        void shouldReturnMakeSkippedWhenFailedAndSkippedButNoneCompleted() {
            // Given: Merge node with 3 predecessors - one FAILED, one SKIPPED, none COMPLETED
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId pred3 = NodeId.step("branch_c");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .addPredecessor(pred3)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred3)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: Merge should be SKIPPED because no branch completed
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Should return MAKE_READY when COMPLETED, SKIPPED, and FAILED all present")
        void shouldReturnMakeReadyWhenCompletedSkippedAndFailed() {
            // Given: Merge node with 3 predecessors - all different terminal states
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId pred3 = NodeId.step("branch_c");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .addPredecessor(pred3)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred3)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: READY because at least one COMPLETED and all are resolved
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when one predecessor FAILED and one still PENDING")
        void shouldReturnStayPendingWhenOneFailedOnePending() {
            // Given: Merge node with 2 predecessors - one failed, one still pending
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.PENDING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: Stay PENDING because not all predecessors are resolved
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should return STAY_PENDING when one FAILED and one RUNNING")
        void shouldReturnStayPendingWhenOneFailedOneRunning() {
            // Given: Merge node with 2 predecessors - one failed, one still running
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.RUNNING);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: Stay PENDING because not all predecessors are resolved
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }

        @Test
        @DisplayName("Should handle explicit MERGE type node with FAILED predecessor")
        void shouldHandleExplicitMergeWithFailedPredecessor() {
            // Given: Explicit MERGE type node
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = new NodeId("core", "wait_all");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MERGE)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then: READY because at least one COMPLETED and all resolved
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Should handle AGGREGATE type node with FAILED predecessor")
        void shouldHandleAggregateWithFailedPredecessor() {
            // Given: AGGREGATE type node (uses same logic as merge)
            NodeId pred1 = NodeId.step("item_1");
            NodeId pred2 = NodeId.step("item_2");
            NodeId aggregateId = new NodeId("core", "collect");
            WorkflowNode aggregateNode = WorkflowNode.builder(aggregateId, WorkflowNode.NodeType.AGGREGATE)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(aggregateNode);

            // Then: READY because at least one COMPLETED and all resolved
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }
    }

    // ========================================================================
    // propagateFailureToSuccessors() Tests
    // ========================================================================

    @Nested
    @DisplayName("propagateFailureToSuccessors()")
    class PropagateFailureToSuccessorsTests {

        @Test
        @DisplayName("Should recalculate merge node when predecessor fails")
        void shouldRecalculateMergeNodeWhenPredecessorFails() {
            // Given: A failed node with a merge node successor
            NodeId failedId = NodeId.step("branch_a");
            NodeId completedPred = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");

            WorkflowNode failedNode = WorkflowNode.builder(failedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(mergeId)
                    .build();
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(failedId)
                    .addPredecessor(completedPred)
                    .build();

            when(graph.getNodeOrNull(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(mergeId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(failedId)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(completedPred)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateFailureToSuccessors(failedNode);

            // Then: The merge node should become READY (recalculated)
            verify(statusUpdater).transitionTo(mergeId, NodeStatus.READY);
        }

        @Test
        @DisplayName("Should skip single-predecessor successor when predecessor fails")
        void shouldSkipSinglePredecessorSuccessorWhenPredecessorFails() {
            // Given: A failed node with a single-predecessor successor
            NodeId failedId = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");

            WorkflowNode failedNode = WorkflowNode.builder(failedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(failedId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.PENDING);

            // When
            propagator.propagateFailureToSuccessors(failedNode);

            // Then: Successor should be skipped
            verify(statusUpdater).markSkipped(successorId);
        }

        @Test
        @DisplayName("Should not propagate to already terminal successor")
        void shouldNotPropagateToAlreadyTerminalSuccessor() {
            // Given: A failed node with a successor already COMPLETED
            NodeId failedId = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");

            WorkflowNode failedNode = WorkflowNode.builder(failedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();
            WorkflowNode successorNode = WorkflowNode.builder(successorId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(failedId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(successorNode);
            when(statusProvider.getStatus(successorId)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateFailureToSuccessors(failedNode);

            // Then: No transitions should occur
            verify(statusUpdater, never()).transitionTo(any(), any());
            verify(statusUpdater, never()).markSkipped(any());
        }

        @Test
        @DisplayName("Should handle null successor gracefully")
        void shouldHandleNullSuccessorGracefully() {
            // Given
            NodeId failedId = NodeId.step("step1");
            NodeId successorId = NodeId.step("step2");

            WorkflowNode failedNode = WorkflowNode.builder(failedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(successorId)
                    .build();

            when(graph.getNodeOrNull(successorId)).thenReturn(null);

            // When / Then - should not throw
            assertDoesNotThrow(() -> propagator.propagateFailureToSuccessors(failedNode));
        }

        @Test
        @DisplayName("Should recalculate explicit MERGE node when predecessor fails")
        void shouldRecalculateExplicitMergeWhenPredecessorFails() {
            // Given: A failed node leading to an explicit MERGE node
            NodeId failedId = NodeId.step("branch_a");
            NodeId completedPred = NodeId.step("branch_b");
            NodeId mergeId = new NodeId("core", "wait_all");

            WorkflowNode failedNode = WorkflowNode.builder(failedId, WorkflowNode.NodeType.MCP)
                    .addSuccessor(mergeId)
                    .build();
            // Explicit MERGE type with single predecessor means isMergeNode() returns false
            // but it has MERGE type, so predecessors > 1 check handles it
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MERGE)
                    .addPredecessor(failedId)
                    .addPredecessor(completedPred)
                    .build();

            when(graph.getNodeOrNull(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(mergeId)).thenReturn(NodeStatus.PENDING);
            when(graph.getNode(mergeId)).thenReturn(mergeNode);
            when(statusProvider.getStatus(failedId)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(completedPred)).thenReturn(NodeStatus.COMPLETED);

            // When
            propagator.propagateFailureToSuccessors(failedNode);

            // Then: Merge should be recalculated and become READY
            verify(statusUpdater).transitionTo(mergeId, NodeStatus.READY);
        }
    }

    // ========================================================================
    // Integration-style Tests - Fork/Merge with Failure Scenario
    // ========================================================================

    @Nested
    @DisplayName("Fork/Merge with Failure - Integration Scenario")
    class ForkMergeWithFailureTests {

        @Test
        @DisplayName("Fork -> 2 branches, one fails -> merge should still become READY")
        void forkWithOneBranchFailingMergeShouldBecomeReady() {
            // This tests the full scenario described in the bug:
            // Fork -> branch_a (COMPLETED) -> merge
            // Fork -> branch_b (FAILED)    -> merge
            // Expected: merge becomes READY
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result,
                "Merge should become READY when all predecessors are resolved, even if some FAILED");
        }

        @Test
        @DisplayName("Fork -> 3 branches, two fail -> merge should still become READY if one completed")
        void forkWithTwoBranchesFailingMergeShouldBecomeReady() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId pred3 = NodeId.step("branch_c");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .addPredecessor(pred3)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred3)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Fork -> all branches fail -> merge should become SKIPPED")
        void forkWithAllBranchesFailingMergeShouldBeSkipped() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.FAILED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.FAILED);

            // When
            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);

            // Then
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result,
                "Merge should be SKIPPED when all branches FAILED (none COMPLETED)");
        }

        @Test
        @DisplayName("Existing behavior preserved: all COMPLETED -> READY")
        void existingBehaviorAllCompletedStillWorks() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.COMPLETED);

            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Existing behavior preserved: COMPLETED + SKIPPED -> READY")
        void existingBehaviorCompletedAndSkippedStillWorks() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);

            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_READY, result);
        }

        @Test
        @DisplayName("Existing behavior preserved: all SKIPPED -> SKIPPED")
        void existingBehaviorAllSkippedStillWorks() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.SKIPPED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.SKIPPED);

            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);
            assertEquals(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED, result);
        }

        @Test
        @DisplayName("Existing behavior preserved: COMPLETED + PENDING -> STAY_PENDING")
        void existingBehaviorCompletedAndPendingStillPending() {
            NodeId pred1 = NodeId.step("branch_a");
            NodeId pred2 = NodeId.step("branch_b");
            NodeId mergeId = NodeId.step("merge_point");
            WorkflowNode mergeNode = WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(pred1)
                    .addPredecessor(pred2)
                    .build();

            when(statusProvider.getStatus(pred1)).thenReturn(NodeStatus.COMPLETED);
            when(statusProvider.getStatus(pred2)).thenReturn(NodeStatus.PENDING);

            ReadyPropagator.ReadyCheckResult result = propagator.checkIfReady(mergeNode);
            assertEquals(ReadyPropagator.ReadyCheckResult.STAY_PENDING, result);
        }
    }

    // ========================================================================
    // ReadyCheckResult Enum Tests
    // ========================================================================

    @Nested
    @DisplayName("ReadyCheckResult Enum")
    class ReadyCheckResultTests {

        @Test
        @DisplayName("Should have all expected values")
        void shouldHaveAllExpectedValues() {
            assertEquals(3, ReadyPropagator.ReadyCheckResult.values().length);
            assertNotNull(ReadyPropagator.ReadyCheckResult.MAKE_READY);
            assertNotNull(ReadyPropagator.ReadyCheckResult.MAKE_SKIPPED);
            assertNotNull(ReadyPropagator.ReadyCheckResult.STAY_PENDING);
        }
    }
}
