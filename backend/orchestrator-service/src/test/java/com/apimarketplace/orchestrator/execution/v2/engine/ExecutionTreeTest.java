package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExecutionTree record and its Builder.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionTree")
class ExecutionTreeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private ExecutionNode mockNode1;

    @Mock
    private ExecutionNode mockNode2;

    @Nested
    @DisplayName("Record fields")
    class RecordFields {

        @Test
        @DisplayName("should store and return all fields correctly")
        void shouldStoreAllFields() {
            ExecutionTree tree = new ExecutionTree(
                "run-1", "workflow-run-1", "tenant-1",
                mockPlan, List.of(mockNode1), ExecutionMode.AUTOMATIC
            );

            assertEquals("run-1", tree.getRunId());
            assertEquals("workflow-run-1", tree.getWorkflowRunId());
            assertEquals("tenant-1", tree.getTenantId());
            assertEquals(mockPlan, tree.getPlan());
            assertEquals(1, tree.getRootNodes().size());
            assertEquals(ExecutionMode.AUTOMATIC, tree.getExecutionMode());
        }
    }

    @Nested
    @DisplayName("getRootNodes")
    class GetRootNodes {

        @Test
        @DisplayName("should return empty list when rootNodes is null")
        void shouldReturnEmptyListForNull() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, null, ExecutionMode.AUTOMATIC);
            assertNotNull(tree.getRootNodes());
            assertTrue(tree.getRootNodes().isEmpty());
        }

        @Test
        @DisplayName("should return the root nodes list")
        void shouldReturnRootNodes() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(mockNode1, mockNode2), ExecutionMode.AUTOMATIC);
            assertEquals(2, tree.getRootNodes().size());
        }
    }

    @Nested
    @DisplayName("getRootNode")
    class GetRootNode {

        @Test
        @DisplayName("should return first root node")
        void shouldReturnFirstRoot() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(mockNode1, mockNode2), ExecutionMode.AUTOMATIC);
            assertEquals(mockNode1, tree.getRootNode());
        }

        @Test
        @DisplayName("should return null when no roots")
        void shouldReturnNullWhenEmpty() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(), ExecutionMode.AUTOMATIC);
            assertNull(tree.getRootNode());
        }

        @Test
        @DisplayName("should return null when rootNodes is null")
        void shouldReturnNullForNullRoots() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, null, ExecutionMode.AUTOMATIC);
            assertNull(tree.getRootNode());
        }
    }

    @Nested
    @DisplayName("hasMultipleWorkflows")
    class HasMultipleWorkflows {

        @Test
        @DisplayName("should return true for multiple roots")
        void shouldReturnTrueForMultipleRoots() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(mockNode1, mockNode2), ExecutionMode.AUTOMATIC);
            assertTrue(tree.hasMultipleWorkflows());
        }

        @Test
        @DisplayName("should return false for single root")
        void shouldReturnFalseForSingleRoot() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(mockNode1), ExecutionMode.AUTOMATIC);
            assertFalse(tree.hasMultipleWorkflows());
        }

        @Test
        @DisplayName("should return false for null roots")
        void shouldReturnFalseForNullRoots() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, null, ExecutionMode.AUTOMATIC);
            assertFalse(tree.hasMultipleWorkflows());
        }
    }

    @Nested
    @DisplayName("getExecutionMode")
    class GetExecutionMode {

        @Test
        @DisplayName("should return AUTOMATIC when mode is null")
        void shouldDefaultToAutomatic() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(), null);
            assertEquals(ExecutionMode.AUTOMATIC, tree.getExecutionMode());
        }

        @Test
        @DisplayName("should return the set mode")
        void shouldReturnSetMode() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(), ExecutionMode.STEP_BY_STEP);
            assertEquals(ExecutionMode.STEP_BY_STEP, tree.getExecutionMode());
        }
    }

    @Nested
    @DisplayName("isStepByStepMode")
    class IsStepByStepMode {

        @Test
        @DisplayName("should return false for null execution mode")
        void shouldReturnFalseForNullMode() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(), null);
            assertFalse(tree.isStepByStepMode());
        }

        @Test
        @DisplayName("should return false for AUTOMATIC mode")
        void shouldReturnFalseForAutomatic() {
            ExecutionTree tree = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(), ExecutionMode.AUTOMATIC);
            assertFalse(tree.isStepByStepMode());
        }
    }

    @Nested
    @DisplayName("withExecutionMode")
    class WithExecutionMode {

        @Test
        @DisplayName("should return new tree with updated mode")
        void shouldReturnNewTreeWithMode() {
            ExecutionTree original = new ExecutionTree("run-1", "wr-1", "t-1", mockPlan, List.of(mockNode1), ExecutionMode.AUTOMATIC);
            ExecutionTree updated = original.withExecutionMode(ExecutionMode.STEP_BY_STEP);

            assertNotSame(original, updated);
            assertEquals(ExecutionMode.STEP_BY_STEP, updated.getExecutionMode());
            assertEquals("run-1", updated.getRunId());
            assertEquals(1, updated.getRootNodes().size());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build tree with all fields")
        void shouldBuildTree() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .workflowRunId("wr-1")
                .tenantId("tenant-1")
                .plan(mockPlan)
                .addRootNode(mockNode1)
                .executionMode(ExecutionMode.AUTOMATIC)
                .build();

            assertEquals("run-1", tree.getRunId());
            assertEquals("wr-1", tree.getWorkflowRunId());
            assertEquals("tenant-1", tree.getTenantId());
            assertEquals(mockPlan, tree.getPlan());
            assertEquals(1, tree.getRootNodes().size());
        }

        @Test
        @DisplayName("should handle rootNode (single) setter")
        void shouldHandleSingleRootNode() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNode(mockNode1)
                .build();

            assertEquals(1, tree.getRootNodes().size());
            assertEquals(mockNode1, tree.getRootNode());
        }

        @Test
        @DisplayName("should handle null rootNode setter")
        void shouldHandleNullRootNode() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNode(null)
                .build();

            assertTrue(tree.getRootNodes().isEmpty());
        }

        @Test
        @DisplayName("should handle rootNodes (multiple) setter")
        void shouldHandleMultipleRootNodes() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNodes(List.of(mockNode1, mockNode2))
                .build();

            assertEquals(2, tree.getRootNodes().size());
        }

        @Test
        @DisplayName("should handle null rootNodes list")
        void shouldHandleNullRootNodesList() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNodes(null)
                .build();

            assertTrue(tree.getRootNodes().isEmpty());
        }

        @Test
        @DisplayName("addRootNode should not add null")
        void addRootNodeShouldSkipNull() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .addRootNode(null)
                .build();

            assertTrue(tree.getRootNodes().isEmpty());
        }

        @Test
        @DisplayName("should default execution mode to AUTOMATIC")
        void shouldDefaultToAutomatic() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .build();

            assertEquals(ExecutionMode.AUTOMATIC, tree.getExecutionMode());
        }

        @Test
        @DisplayName("should handle null execution mode as AUTOMATIC")
        void shouldHandleNullModeAsAutomatic() {
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .executionMode(null)
                .build();

            assertEquals(ExecutionMode.AUTOMATIC, tree.getExecutionMode());
        }
    }
}
