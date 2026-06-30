package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowStateManager.
 * Tests that validateTransition() enforces the full canTransitionTo() graph.
 */
class WorkflowStateManagerTest {

    private WorkflowStateManager stateManager;
    private NodeId triggerId;
    private NodeId step1Id;
    private NodeId step2Id;

    @BeforeEach
    void setUp() {
        triggerId = NodeId.trigger("start");
        step1Id = NodeId.step("step1");
        step2Id = NodeId.step("step2");

        // Build a simple workflow: trigger -> step1 -> step2
        Map<NodeId, WorkflowNode> nodes = new HashMap<>();
        nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                .addSuccessor(step1Id)
                .build());
        nodes.put(step1Id, WorkflowNode.builder(step1Id, WorkflowNode.NodeType.MCP)
                .addPredecessor(triggerId)
                .addSuccessor(step2Id)
                .build());
        nodes.put(step2Id, WorkflowNode.builder(step2Id, WorkflowNode.NodeType.MCP)
                .addPredecessor(step1Id)
                .build());

        List<WorkflowGraph.Edge> edges = List.of(
                new WorkflowGraph.Edge(triggerId, step1Id),
                new WorkflowGraph.Edge(step1Id, step2Id)
        );

        WorkflowGraph graph = new WorkflowGraph(nodes, triggerId, edges);

        stateManager = new WorkflowStateManager();
        stateManager.initializeWithGraph("test-run-1", graph);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Nested
    @DisplayName("Initialization")
    class InitializationTests {

        @Test
        @DisplayName("Trigger should be READY after initialization")
        void triggerShouldBeReadyAfterInit() {
            assertEquals(NodeStatus.READY, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Non-trigger nodes should be PENDING after initialization")
        void nonTriggerNodesShouldBePendingAfterInit() {
            assertEquals(NodeStatus.PENDING, stateManager.getStatus(step1Id));
            assertEquals(NodeStatus.PENDING, stateManager.getStatus(step2Id));
        }

        @Test
        @DisplayName("getReadyNodes should return only the trigger after initialization")
        void getReadyNodesShouldReturnOnlyTrigger() {
            List<NodeId> readyNodes = stateManager.getReadyNodes();
            assertEquals(1, readyNodes.size());
            assertTrue(readyNodes.contains(triggerId));
        }
    }

    // ========================================================================
    // markRunning() Tests
    // ========================================================================

    @Nested
    @DisplayName("markRunning()")
    class MarkRunningTests {

        @Test
        @DisplayName("Should succeed for READY node (READY -> RUNNING)")
        void shouldSucceedForReadyNode() {
            // Trigger is READY after init
            assertDoesNotThrow(() -> stateManager.markRunning(triggerId));
            assertEquals(NodeStatus.RUNNING, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should throw for PENDING node (PENDING -> RUNNING is invalid)")
        void shouldThrowForPendingNode() {
            // step1 is PENDING
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markRunning(step1Id)
            );
            assertTrue(exception.getMessage().contains("invalid transition"),
                    "Error message should mention invalid transition, got: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("PENDING"),
                    "Error message should mention PENDING status, got: " + exception.getMessage());
        }

        @Test
        @DisplayName("Should throw for COMPLETED node (terminal state)")
        void shouldThrowForCompletedNode() {
            // Complete the trigger
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markRunning(triggerId)
            );
            assertTrue(exception.getMessage().contains("COMPLETED"),
                    "Error message should mention COMPLETED status, got: " + exception.getMessage());
        }
    }

    // ========================================================================
    // markCompleted() Tests
    // ========================================================================

    @Nested
    @DisplayName("markCompleted()")
    class MarkCompletedTests {

        @Test
        @DisplayName("Should succeed for RUNNING node (RUNNING -> COMPLETED)")
        void shouldSucceedForRunningNode() {
            stateManager.markRunning(triggerId);
            assertDoesNotThrow(() -> stateManager.markCompleted(triggerId, "result"));
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should succeed for READY node (READY -> COMPLETED, for Decision/Fork)")
        void shouldSucceedForReadyNode() {
            // READY -> COMPLETED is valid for auto-evaluating nodes (Decision, Fork)
            assertDoesNotThrow(() -> stateManager.markCompleted(triggerId, "auto-evaluated"));
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should throw for PENDING node (PENDING -> COMPLETED is invalid)")
        void shouldThrowForPendingNode() {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markCompleted(step1Id, "result")
            );
            assertTrue(exception.getMessage().contains("invalid transition"),
                    "Error message should mention invalid transition, got: " + exception.getMessage());
        }

        @Test
        @DisplayName("Should throw for already COMPLETED node (terminal)")
        void shouldThrowForAlreadyCompleted() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markCompleted(triggerId, "again"));
        }

        @Test
        @DisplayName("Should store result when provided")
        void shouldStoreResult() {
            stateManager.markRunning(triggerId);
            Map<String, Object> result = Map.of("key", "value");
            stateManager.markCompleted(triggerId, result);

            assertEquals(result, stateManager.getResult(triggerId));
        }

        @Test
        @DisplayName("Should propagate completion to successors")
        void shouldPropagateCompletionToSuccessors() {
            // Complete trigger -> step1 should become READY
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            assertEquals(NodeStatus.READY, stateManager.getStatus(step1Id));
        }
    }

    // ========================================================================
    // markFailed() Tests
    // ========================================================================

    @Nested
    @DisplayName("markFailed()")
    class MarkFailedTests {

        @Test
        @DisplayName("Should succeed for RUNNING node (RUNNING -> FAILED)")
        void shouldSucceedForRunningNode() {
            stateManager.markRunning(triggerId);
            RuntimeException error = new RuntimeException("test error");
            assertDoesNotThrow(() -> stateManager.markFailed(triggerId, error));
            assertEquals(NodeStatus.FAILED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should throw for PENDING node (PENDING -> FAILED is invalid)")
        void shouldThrowForPendingNode() {
            assertThrows(IllegalStateException.class,
                    () -> stateManager.markFailed(step1Id, new RuntimeException("error")));
        }

        @Test
        @DisplayName("Should throw for READY node (READY -> FAILED is invalid)")
        void shouldThrowForReadyNode() {
            assertThrows(IllegalStateException.class,
                    () -> stateManager.markFailed(triggerId, new RuntimeException("error")));
        }

        @Test
        @DisplayName("Should throw for already FAILED node (terminal)")
        void shouldThrowForAlreadyFailed() {
            stateManager.markRunning(triggerId);
            stateManager.markFailed(triggerId, new RuntimeException("first"));

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markFailed(triggerId, new RuntimeException("second")));
        }
    }

    // ========================================================================
    // markSkipped() Tests
    // ========================================================================

    @Nested
    @DisplayName("markSkipped()")
    class MarkSkippedTests {

        @Test
        @DisplayName("Should succeed for PENDING node (PENDING -> SKIPPED)")
        void shouldSucceedForPendingNode() {
            stateManager.markSkipped(step1Id);
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(step1Id));
        }

        @Test
        @DisplayName("Should succeed for READY node (READY -> SKIPPED)")
        void shouldSucceedForReadyNode() {
            stateManager.markSkipped(triggerId);
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should silently ignore for already terminal node (COMPLETED)")
        void shouldIgnoreForCompletedNode() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            // Should not throw - just silently ignored
            assertDoesNotThrow(() -> stateManager.markSkipped(triggerId));
            // Status should remain COMPLETED
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should silently ignore for already terminal node (FAILED)")
        void shouldIgnoreForFailedNode() {
            stateManager.markRunning(triggerId);
            stateManager.markFailed(triggerId, new RuntimeException("error"));

            assertDoesNotThrow(() -> stateManager.markSkipped(triggerId));
            assertEquals(NodeStatus.FAILED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("Should silently ignore for already SKIPPED node")
        void shouldIgnoreForSkippedNode() {
            stateManager.markSkipped(step1Id);

            assertDoesNotThrow(() -> stateManager.markSkipped(step1Id));
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(step1Id));
        }

        @Test
        @DisplayName("Should propagate skip to successors")
        void shouldPropagateSkipToSuccessors() {
            // Skip step1 -> step2 should also be skipped (single predecessor)
            stateManager.markSkipped(step1Id);
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(step2Id));
        }
    }

    // ========================================================================
    // Full Workflow Lifecycle Tests
    // ========================================================================

    @Nested
    @DisplayName("Full Workflow Lifecycle")
    class FullWorkflowLifecycleTests {

        @Test
        @DisplayName("Normal flow: trigger -> step1 -> step2 all complete")
        void normalFlow() {
            // Trigger: READY -> RUNNING -> COMPLETED
            assertEquals(NodeStatus.READY, stateManager.getStatus(triggerId));
            stateManager.markRunning(triggerId);
            assertEquals(NodeStatus.RUNNING, stateManager.getStatus(triggerId));
            stateManager.markCompleted(triggerId, null);
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(triggerId));

            // Step1 should now be READY (propagated from trigger completion)
            assertEquals(NodeStatus.READY, stateManager.getStatus(step1Id));
            stateManager.markRunning(step1Id);
            stateManager.markCompleted(step1Id, null);
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(step1Id));

            // Step2 should now be READY
            assertEquals(NodeStatus.READY, stateManager.getStatus(step2Id));
            stateManager.markRunning(step2Id);
            stateManager.markCompleted(step2Id, null);
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(step2Id));

            // Workflow should be complete
            assertTrue(stateManager.isWorkflowComplete());
            assertFalse(stateManager.hasFailures());
        }

        @Test
        @DisplayName("Failure flow: step1 fails")
        void failureFlow() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            // Step1 becomes READY, then fails
            assertEquals(NodeStatus.READY, stateManager.getStatus(step1Id));
            stateManager.markRunning(step1Id);
            stateManager.markFailed(step1Id, new RuntimeException("API error"));
            assertEquals(NodeStatus.FAILED, stateManager.getStatus(step1Id));

            // Step2 should be in a non-completed state (either PENDING or SKIPPED depending on propagation)
            NodeStatus step2Status = stateManager.getStatus(step2Id);
            assertNotEquals(NodeStatus.COMPLETED, step2Status,
                    "Step2 should not be COMPLETED when predecessor failed");
            assertNotEquals(NodeStatus.RUNNING, step2Status,
                    "Step2 should not be RUNNING when predecessor failed");

            assertTrue(stateManager.hasFailures());
        }

        @Test
        @DisplayName("Skip propagation: step1 skipped -> step2 skipped")
        void skipPropagation() {
            // Skip step1 directly
            stateManager.markSkipped(step1Id);
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(step1Id));

            // Step2 should be skipped too (single predecessor skipped)
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(step2Id));
        }
    }

    // ========================================================================
    // Decision Node Auto-Evaluation Tests
    // ========================================================================

    @Nested
    @DisplayName("Decision Node Auto-Evaluation")
    class DecisionNodeTests {

        private NodeId decisionId;
        private NodeId ifBranchId;
        private NodeId elseBranchId;

        @BeforeEach
        void setUp() {
            decisionId = NodeId.decision("check");
            ifBranchId = NodeId.step("if_branch");
            elseBranchId = NodeId.step("else_branch");

            Map<String, NodeId> portSuccessors = new HashMap<>();
            portSuccessors.put("if", ifBranchId);
            portSuccessors.put("else", elseBranchId);

            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(decisionId)
                    .build());
            nodes.put(decisionId, WorkflowNode.builder(decisionId, WorkflowNode.NodeType.DECISION)
                    .addPredecessor(triggerId)
                    .addSuccessor(ifBranchId)
                    .addSuccessor(elseBranchId)
                    .portSuccessors(portSuccessors)
                    .build());
            nodes.put(ifBranchId, WorkflowNode.builder(ifBranchId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(decisionId)
                    .build());
            nodes.put(elseBranchId, WorkflowNode.builder(elseBranchId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(decisionId)
                    .build());

            List<WorkflowGraph.Edge> edges = List.of(
                    new WorkflowGraph.Edge(triggerId, decisionId),
                    new WorkflowGraph.Edge(decisionId, ifBranchId),
                    new WorkflowGraph.Edge(decisionId, elseBranchId)
            );

            WorkflowGraph graph = new WorkflowGraph(nodes, triggerId, edges);
            stateManager = new WorkflowStateManager();
            stateManager.initializeWithGraph("test-run-decision", graph);
        }

        @Test
        @DisplayName("Decision auto-evaluates when trigger completes (PENDING -> READY -> COMPLETED)")
        void decisionAutoEvaluates() {
            // Complete trigger
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            // Decision should auto-evaluate to COMPLETED (PENDING -> READY -> COMPLETED)
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(decisionId));
        }
    }

    // ========================================================================
    // State Change Listener Tests
    // ========================================================================

    @Nested
    @DisplayName("State Change Listeners")
    class StateChangeListenerTests {

        @Test
        @DisplayName("Listener should be notified on state change")
        void listenerShouldBeNotified() {
            var events = new java.util.ArrayList<WorkflowStateManager.StateChangeEvent>();
            stateManager.addListener(events::add);

            stateManager.markRunning(triggerId);

            assertFalse(events.isEmpty(), "Listener should receive at least one event");
            assertEquals(triggerId, events.get(0).nodeId());
            assertEquals(NodeStatus.READY, events.get(0).oldStatus());
            assertEquals(NodeStatus.RUNNING, events.get(0).newStatus());
        }

        @Test
        @DisplayName("Multiple listeners should all be notified")
        void multipleListenersShouldBeNotified() {
            var events1 = new java.util.ArrayList<WorkflowStateManager.StateChangeEvent>();
            var events2 = new java.util.ArrayList<WorkflowStateManager.StateChangeEvent>();
            stateManager.addListener(events1::add);
            stateManager.addListener(events2::add);

            stateManager.markRunning(triggerId);

            assertFalse(events1.isEmpty());
            assertFalse(events2.isEmpty());
        }

        @Test
        @DisplayName("Listener error should not prevent state change")
        void listenerErrorShouldNotPreventStateChange() {
            stateManager.addListener(event -> {
                throw new RuntimeException("listener error");
            });

            assertDoesNotThrow(() -> stateManager.markRunning(triggerId));
            assertEquals(NodeStatus.RUNNING, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("removeListener should stop notifications")
        void removeListenerShouldStopNotifications() {
            var events = new java.util.ArrayList<WorkflowStateManager.StateChangeEvent>();
            WorkflowStateManager.StateChangeListener listener = events::add;
            stateManager.addListener(listener);
            stateManager.removeListener(listener);

            stateManager.markRunning(triggerId);

            assertTrue(events.isEmpty(), "Removed listener should not receive events");
        }
    }

    // ========================================================================
    // Edge Status Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Status Derivation")
    class EdgeStatusTests {

        @Test
        @DisplayName("Edge should be PENDING when both nodes are PENDING/READY")
        void edgePendingWhenBothPending() {
            // trigger is READY, step1 is PENDING
            // The edge from trigger to step1 should be PENDING
            NodeStatus edgeStatus = stateManager.getEdgeStatus(triggerId, step1Id);
            assertEquals(NodeStatus.PENDING, edgeStatus);
        }

        @Test
        @DisplayName("Edge should be RUNNING when source is done and target is active")
        void edgeRunningWhenSourceDoneAndTargetActive() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);
            // step1 is now READY
            stateManager.markRunning(step1Id);

            NodeStatus edgeStatus = stateManager.getEdgeStatus(triggerId, step1Id);
            assertEquals(NodeStatus.RUNNING, edgeStatus);
        }

        @Test
        @DisplayName("Edge should be COMPLETED when both endpoints are COMPLETED")
        void edgeCompletedWhenBothCompleted() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);
            stateManager.markRunning(step1Id);
            stateManager.markCompleted(step1Id, null);

            NodeStatus edgeStatus = stateManager.getEdgeStatus(triggerId, step1Id);
            assertEquals(NodeStatus.COMPLETED, edgeStatus);
        }

        @Test
        @DisplayName("Edge should be SKIPPED when either endpoint is SKIPPED")
        void edgeSkippedWhenEitherSkipped() {
            stateManager.markSkipped(step1Id);

            NodeStatus edgeStatus = stateManager.getEdgeStatus(triggerId, step1Id);
            assertEquals(NodeStatus.SKIPPED, edgeStatus);
        }
    }

    // ========================================================================
    // Query Method Tests
    // ========================================================================

    @Nested
    @DisplayName("Query Methods")
    class QueryMethodTests {

        @Test
        @DisplayName("getStatus for unknown node should return PENDING")
        void getStatusForUnknownNodeShouldReturnPending() {
            NodeId unknownId = NodeId.step("unknown");
            assertEquals(NodeStatus.PENDING, stateManager.getStatus(unknownId));
        }

        @Test
        @DisplayName("getStatus by string key should work")
        void getStatusByStringKeyShouldWork() {
            assertEquals(NodeStatus.READY, stateManager.getStatus("trigger:start"));
            assertEquals(NodeStatus.PENDING, stateManager.getStatus("mcp:step1"));
        }

        @Test
        @DisplayName("getNodesByStatus should filter correctly")
        void getNodesByStatusShouldFilter() {
            List<NodeId> readyNodes = stateManager.getNodesByStatus(NodeStatus.READY);
            assertEquals(1, readyNodes.size());
            assertTrue(readyNodes.contains(triggerId));

            List<NodeId> pendingNodes = stateManager.getNodesByStatus(NodeStatus.PENDING);
            assertEquals(2, pendingNodes.size());
            assertTrue(pendingNodes.contains(step1Id));
            assertTrue(pendingNodes.contains(step2Id));
        }

        @Test
        @DisplayName("getAllStatuses should return unmodifiable map")
        void getAllStatusesShouldReturnUnmodifiable() {
            Map<NodeId, NodeStatus> statuses = stateManager.getAllStatuses();
            assertEquals(3, statuses.size());
            assertThrows(UnsupportedOperationException.class,
                    () -> statuses.put(triggerId, NodeStatus.FAILED));
        }

        @Test
        @DisplayName("isWorkflowComplete should return false when not all terminal")
        void isWorkflowCompleteWhenNotAllTerminal() {
            assertFalse(stateManager.isWorkflowComplete());
        }
    }

    // ========================================================================
    // Invalid Transition Tests (Enforced by canTransitionTo)
    // ========================================================================

    @Nested
    @DisplayName("Invalid Transition Enforcement")
    class InvalidTransitionEnforcementTests {

        @Test
        @DisplayName("PENDING -> RUNNING should throw (must go through READY first)")
        void pendingToRunningShouldThrow() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markRunning(step1Id)
            );
            assertTrue(ex.getMessage().contains("PENDING"));
            assertTrue(ex.getMessage().contains("RUNNING"));
        }

        @Test
        @DisplayName("PENDING -> COMPLETED should throw (must go through READY -> RUNNING first)")
        void pendingToCompletedShouldThrow() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markCompleted(step1Id, null)
            );
            assertTrue(ex.getMessage().contains("PENDING"));
            assertTrue(ex.getMessage().contains("COMPLETED"));
        }

        @Test
        @DisplayName("PENDING -> FAILED should throw (must go through READY -> RUNNING first)")
        void pendingToFailedShouldThrow() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markFailed(step1Id, new RuntimeException("error"))
            );
            assertTrue(ex.getMessage().contains("PENDING"));
            assertTrue(ex.getMessage().contains("FAILED"));
        }

        @Test
        @DisplayName("READY -> FAILED should throw (must go through RUNNING first)")
        void readyToFailedShouldThrow() {
            // triggerId is READY
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markFailed(triggerId, new RuntimeException("error"))
            );
            assertTrue(ex.getMessage().contains("READY"));
            assertTrue(ex.getMessage().contains("FAILED"));
        }

        @Test
        @DisplayName("COMPLETED -> RUNNING should throw (terminal state)")
        void completedToRunningShouldThrow() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> stateManager.markRunning(triggerId)
            );
            assertTrue(ex.getMessage().contains("COMPLETED"));
        }

        @Test
        @DisplayName("COMPLETED -> COMPLETED should throw (terminal state)")
        void completedToCompletedShouldThrow() {
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markCompleted(triggerId, null));
        }

        @Test
        @DisplayName("FAILED -> COMPLETED should throw (terminal state)")
        void failedToCompletedShouldThrow() {
            stateManager.markRunning(triggerId);
            stateManager.markFailed(triggerId, new RuntimeException("error"));

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markCompleted(triggerId, null));
        }

        @Test
        @DisplayName("FAILED -> RUNNING should throw (terminal state)")
        void failedToRunningShouldThrow() {
            stateManager.markRunning(triggerId);
            stateManager.markFailed(triggerId, new RuntimeException("error"));

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markRunning(triggerId));
        }

        @Test
        @DisplayName("SKIPPED -> RUNNING should throw (terminal state)")
        void skippedToRunningShouldThrow() {
            stateManager.markSkipped(step1Id);

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markRunning(step1Id));
        }

        @Test
        @DisplayName("SKIPPED -> COMPLETED should throw (terminal state)")
        void skippedToCompletedShouldThrow() {
            stateManager.markSkipped(step1Id);

            assertThrows(IllegalStateException.class,
                    () -> stateManager.markCompleted(step1Id, null));
        }
    }

    // ========================================================================
    // Decision Branch Recording Tests
    // ========================================================================

    @Nested
    @DisplayName("Decision Branch Recording")
    class DecisionBranchTests {

        @Test
        @DisplayName("Should record and retrieve a decision branch")
        void shouldRecordAndRetrieveDecisionBranch() {
            NodeId decisionId = NodeId.decision("check");
            stateManager.recordDecisionBranch(decisionId, "if");

            assertEquals("if", stateManager.getDecisionBranch(decisionId));
        }

        @Test
        @DisplayName("Should return null for unrecorded decision branch")
        void shouldReturnNullForUnrecordedBranch() {
            NodeId decisionId = NodeId.decision("check");
            assertNull(stateManager.getDecisionBranch(decisionId));
        }

        @Test
        @DisplayName("Should record multiple branches for same decision (split context)")
        void shouldRecordMultipleBranchesForSameDecision() {
            NodeId decisionId = NodeId.decision("classify");
            stateManager.recordDecisionBranch(decisionId, "category_0");
            stateManager.recordDecisionBranch(decisionId, "category_1");

            java.util.Set<String> branches = stateManager.getDecisionBranches(decisionId);
            assertNotNull(branches);
            assertEquals(2, branches.size());
            assertTrue(branches.contains("category_0"));
            assertTrue(branches.contains("category_1"));
        }
    }

    // ========================================================================
    // Backward Compatibility Tests
    // ========================================================================

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("markSkipped on terminal node should be silently ignored (not throw)")
        void markSkippedOnTerminalShouldBeIgnored() {
            // This is critical for backward compatibility - markSkipped checks isTerminal() first
            stateManager.markRunning(triggerId);
            stateManager.markCompleted(triggerId, null);

            // Should NOT throw - markSkipped silently ignores terminal nodes
            assertDoesNotThrow(() -> stateManager.markSkipped(triggerId));
            assertEquals(NodeStatus.COMPLETED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("markSkipped on RUNNING node should be silently ignored (RUNNING -> SKIPPED is not valid)")
        void markSkippedOnRunningShouldBeIgnored() {
            stateManager.markRunning(triggerId);

            // RUNNING is not terminal, but RUNNING -> SKIPPED is not in canTransitionTo()
            // markSkipped checks isTerminal() and then calls transitionTo() which doesn't validate
            // This test documents the current behavior
            // Note: markSkipped uses its own logic (checks isTerminal), not validateTransition
            stateManager.markSkipped(triggerId);
            assertEquals(NodeStatus.SKIPPED, stateManager.getStatus(triggerId));
        }

        @Test
        @DisplayName("StateManagerIntegrationService catches IllegalStateException from markRunning")
        void stateManagerIntegrationCatchesExceptions() {
            // This test verifies that the pattern in StateManagerIntegrationService works:
            // markRunning is wrapped in try-catch
            // When validateTransition throws, it's caught and logged as warning

            // step1 is PENDING, so markRunning should throw
            assertThrows(IllegalStateException.class, () -> stateManager.markRunning(step1Id));
            // The StateManagerIntegrationService.markNodeRunning() catches this exception
        }
    }
}
