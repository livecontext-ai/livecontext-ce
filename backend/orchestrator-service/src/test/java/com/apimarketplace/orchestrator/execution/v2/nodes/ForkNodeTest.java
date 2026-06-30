package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ForkNode.
 * ForkNode activates ALL branches in parallel (unlike Decision which selects ONE).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForkNode")
class ForkNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create ForkNode with nodeId and branches")
        void shouldCreateForkNodeWithNodeIdAndBranches() {
            List<ForkNode.ForkBranch> branches = new ArrayList<>();
            branches.add(new ForkNode.ForkBranch("branch_0", "Branch A", new ArrayList<>()));
            branches.add(new ForkNode.ForkBranch("branch_1", "Branch B", new ArrayList<>()));

            ForkNode node = new ForkNode("core:fork", branches);

            assertEquals("core:fork", node.getNodeId());
            assertEquals(NodeType.FORK, node.getType());
            assertEquals(2, node.getBranches().size());
        }

        @Test
        @DisplayName("Should create ForkNode with only nodeId")
        void shouldCreateForkNodeWithOnlyNodeId() {
            ForkNode node = new ForkNode("core:fork");

            assertEquals("core:fork", node.getNodeId());
            assertEquals(NodeType.FORK, node.getType());
            assertTrue(node.getBranches().isEmpty());
        }

        @Test
        @DisplayName("Should handle null branches")
        void shouldHandleNullBranches() {
            ForkNode node = new ForkNode("core:fork", null);

            assertNotNull(node.getBranches());
            assertTrue(node.getBranches().isEmpty());
        }

        @Test
        @DisplayName("Should create ForkNode using builder")
        void shouldCreateForkNodeUsingBuilder() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:parallel")
                .addBranch("branch_0", "Task A")
                .addBranch("branch_1", "Task B")
                .addBranch("branch_2", "Task C")
                .build();

            assertEquals("core:parallel", node.getNodeId());
            assertEquals(3, node.getBranches().size());
        }

        @Test
        @DisplayName("Should create ForkNode with branches containing nodes")
        void shouldCreateForkNodeWithBranchesContainingNodes() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");

            ForkNode node = ForkNode.builder()
                .nodeId("core:parallel")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .build();

            assertEquals(2, node.getBranches().size());
            assertEquals(1, node.getBranches().get(0).nodes().size());
            assertEquals(1, node.getBranches().get(1).nodes().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should return success")
        void shouldReturnSuccess() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .addBranch("branch_1", "Branch B")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should include node_type FORK in output")
        void shouldIncludeNodeTypeFORKInOutput() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("FORK", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should include fork_node in output")
        void shouldIncludeForkNodeInOutput() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:my_fork")
                .addBranch("branch_0", "Branch A")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("core:my_fork", result.output().get("fork_node"));
        }

        @Test
        @DisplayName("Should include branch_count in output")
        void shouldIncludeBranchCountInOutput() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .addBranch("branch_1", "Branch B")
                .addBranch("branch_2", "Branch C")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("branch_count"));
        }

        @Test
        @DisplayName("Should include branches list in output")
        void shouldIncludeBranchesListInOutput() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A")
                .addBranch("branch_1", "Task B")
                .build();

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branches = (List<Map<String, Object>>) result.output().get("branches");

            assertNotNull(branches);
            assertEquals(2, branches.size());
            assertEquals("branch_0", branches.get(0).get("id"));
            assertEquals("Task A", branches.get(0).get("label"));
            assertEquals("branch_1", branches.get(1).get("id"));
            assertEquals("Task B", branches.get(1).get("label"));
        }

        @Test
        @DisplayName("Should include item context in output")
        void shouldIncludeItemContextInOutput() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
        }

        @Test
        @DisplayName("Should handle empty branches")
        void shouldHandleEmptyBranches() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("branch_count"));
        }

        @Test
        @DisplayName("Should use default id when branch id is null")
        void shouldUseDefaultIdWhenBranchIdIsNull() {
            List<ForkNode.ForkBranch> branches = new ArrayList<>();
            branches.add(new ForkNode.ForkBranch(null, "Branch A", new ArrayList<>()));

            ForkNode node = new ForkNode("core:fork", branches);

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branchList = (List<Map<String, Object>>) result.output().get("branches");

            assertEquals("branch_0", branchList.get(0).get("id"));
        }

        @Test
        @DisplayName("Should use default label when branch label is null")
        void shouldUseDefaultLabelWhenBranchLabelIsNull() {
            List<ForkNode.ForkBranch> branches = new ArrayList<>();
            branches.add(new ForkNode.ForkBranch("branch_0", null, new ArrayList<>()));

            ForkNode node = new ForkNode("core:fork", branches);

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branchList = (List<Map<String, Object>>) result.output().get("branches");

            assertEquals("Branch 0", branchList.get(0).get("label"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests - ALL branches execute
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes() - ALL Branches Execute")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return ALL branch target nodes")
        void shouldReturnAllBranchTargetNodes() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");
            ExecutionNode taskC = createMockNode("mcp:task_c");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .addBranch("branch_2", "Task C", new ArrayList<>(List.of(taskC)))
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(3, nextNodes.size());
            assertTrue(nextNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_a")));
            assertTrue(nextNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_b")));
            assertTrue(nextNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_c")));
        }

        @Test
        @DisplayName("Should include direct successors")
        void shouldIncludeDirectSuccessors() {
            ExecutionNode branchTarget = createMockNode("mcp:branch_target");
            ExecutionNode directSuccessor = createMockNode("mcp:direct_successor");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch", new ArrayList<>(List.of(branchTarget)))
                .build();
            node.addSuccessor(directSuccessor);

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
            assertTrue(nextNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:branch_target")));
            assertTrue(nextNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:direct_successor")));
        }

        @Test
        @DisplayName("Should return empty list when no branches and no successors")
        void shouldReturnEmptyListWhenNoBranchesAndNoSuccessors() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return nodes from all branches even with multiple nodes per branch")
        void shouldReturnNodesFromAllBranchesEvenWithMultipleNodesPerBranch() {
            ExecutionNode taskA1 = createMockNode("mcp:task_a1");
            ExecutionNode taskA2 = createMockNode("mcp:task_a2");
            ExecutionNode taskB1 = createMockNode("mcp:task_b1");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A", new ArrayList<>(List.of(taskA1, taskA2)))
                .addBranch("branch_1", "Branch B", new ArrayList<>(List.of(taskB1)))
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(3, nextNodes.size());
        }

        @Test
        @DisplayName("Should filter out null nodes inside a branch and return only the valid node")
        void shouldFilterOutNullNodesInsideBranch() {
            ExecutionNode validTask = createMockNode("mcp:valid_task");

            // A branch whose node list contains both a valid node and a null entry.
            List<ExecutionNode> branchNodes = new ArrayList<>();
            branchNodes.add(validTask);
            branchNodes.add(null);
            List<ForkNode.ForkBranch> branches = new ArrayList<>();
            branches.add(new ForkNode.ForkBranch("branch_0", "Branch A", branchNodes));

            ForkNode node = new ForkNode("core:fork", branches);

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            // The null branch node is skipped by the 'if (node != null)' guard;
            // only the valid node is returned (no NullPointerException).
            assertEquals(1, nextNodes.size());
            assertEquals("mcp:valid_task", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return valid branch node and skip a sibling branch holding only a null node")
        void shouldReturnValidBranchNodeAndSkipNullOnlyBranch() {
            ExecutionNode validTask = createMockNode("mcp:valid_task");

            // One branch with a valid node, one branch that only carries a null node.
            List<ExecutionNode> validBranchNodes = new ArrayList<>(List.of(validTask));
            List<ExecutionNode> nullBranchNodes = new ArrayList<>();
            nullBranchNodes.add(null);

            List<ForkNode.ForkBranch> branches = new ArrayList<>();
            branches.add(new ForkNode.ForkBranch("branch_0", "Valid", validBranchNodes));
            branches.add(new ForkNode.ForkBranch("branch_1", "NullOnly", nullBranchNodes));

            ForkNode node = new ForkNode("core:fork", branches);

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            // Only the valid branch contributes a node; the null-only branch is filtered out.
            assertEquals(1, nextNodes.size());
            assertEquals("mcp:valid_task", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should filter out a null direct successor while keeping valid branch nodes")
        void shouldFilterOutNullDirectSuccessor() {
            ExecutionNode branchTarget = createMockNode("mcp:branch_target");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch", new ArrayList<>(List.of(branchTarget)))
                .build();
            // A null direct successor must be skipped by the 'if (node != null)' guard.
            node.addSuccessor(null);

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:branch_target", nextNodes.get(0).getNodeId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSkippedChildNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSkippedChildNodes()")
    class GetSkippedChildNodesTests {

        @Test
        @DisplayName("Should return empty list - Fork has no skipped branches")
        void shouldReturnEmptyListForkHasNoSkippedBranches() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result);

            assertTrue(skippedNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fork vs Decision comparison tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fork vs Decision")
    class ForkVsDecisionTests {

        @Test
        @DisplayName("Fork executes ALL branches (parallel)")
        void forkExecutesAllBranchesParallel() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");
            ExecutionNode taskC = createMockNode("mcp:task_c");

            ForkNode forkNode = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .addBranch("branch_2", "Task C", new ArrayList<>(List.of(taskC)))
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());
            List<ExecutionNode> nextNodes = forkNode.getNextNodes(result);

            // Fork: ALL branches are returned
            assertEquals(3, nextNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Branch management tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Branch Management")
    class BranchManagementTests {

        @Test
        @DisplayName("Should add branch dynamically")
        void shouldAddBranchDynamically() {
            ForkNode node = new ForkNode("core:fork");

            assertEquals(0, node.getBranches().size());

            node.addBranch(new ForkNode.ForkBranch("branch_0", "New Branch", new ArrayList<>()));

            assertEquals(1, node.getBranches().size());
        }

        @Test
        @DisplayName("Should get branches")
        void shouldGetBranches() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A")
                .addBranch("branch_1", "Task B")
                .build();

            List<ForkNode.ForkBranch> branches = node.getBranches();

            assertEquals(2, branches.size());
            assertEquals("branch_0", branches.get(0).id());
            assertEquals("Task A", branches.get(0).label());
            assertEquals("branch_1", branches.get(1).id());
            assertEquals("Task B", branches.get(1).label());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ForkBranch record tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ForkBranch Record")
    class ForkBranchRecordTests {

        @Test
        @DisplayName("Should create ForkBranch with full constructor")
        void shouldCreateForkBranchWithFullConstructor() {
            ExecutionNode node = createMockNode("mcp:task");
            List<ExecutionNode> nodes = new ArrayList<>(List.of(node));

            ForkNode.ForkBranch branch = new ForkNode.ForkBranch("branch_0", "Task A", nodes);

            assertEquals("branch_0", branch.id());
            assertEquals("Task A", branch.label());
            assertEquals(1, branch.nodes().size());
        }

        @Test
        @DisplayName("Should create ForkBranch with compact constructor")
        void shouldCreateForkBranchWithCompactConstructor() {
            ForkNode.ForkBranch branch = new ForkNode.ForkBranch("branch_0", "Task A");

            assertEquals("branch_0", branch.id());
            assertEquals("Task A", branch.label());
            assertTrue(branch.nodes().isEmpty());
        }

        @Test
        @DisplayName("Should add node to branch")
        void shouldAddNodeToBranch() {
            ForkNode.ForkBranch branch = new ForkNode.ForkBranch("branch_0", "Task A");
            ExecutionNode node = createMockNode("mcp:task");

            branch.addNode(node);

            assertEquals(1, branch.nodes().size());
            assertEquals("mcp:task", branch.nodes().get(0).getNodeId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAllChildNodes() tests - for skip propagation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllChildNodes() - Skip Propagation")
    class GetAllChildNodesTests {

        @Test
        @DisplayName("Should return all nodes from all branches plus successors")
        void shouldReturnAllNodesFromAllBranchesPlusSuccessors() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");
            ExecutionNode taskC = createMockNode("mcp:task_c");
            ExecutionNode directSuccessor = createMockNode("mcp:after_fork");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .addBranch("branch_2", "Task C", new ArrayList<>(List.of(taskC)))
                .build();
            node.addSuccessor(directSuccessor);

            List<ExecutionNode> allChildren = node.getAllChildNodes();

            assertEquals(4, allChildren.size());
            assertTrue(allChildren.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_a")));
            assertTrue(allChildren.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_b")));
            assertTrue(allChildren.stream().anyMatch(n -> n.getNodeId().equals("mcp:task_c")));
            assertTrue(allChildren.stream().anyMatch(n -> n.getNodeId().equals("mcp:after_fork")));
        }

        @Test
        @DisplayName("Should return empty list when no branches and no successors")
        void shouldReturnEmptyWhenNoBranchesAndNoSuccessors() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .build();

            List<ExecutionNode> allChildren = node.getAllChildNodes();

            assertTrue(allChildren.isEmpty());
        }

        @Test
        @DisplayName("Should return only successors when no branches")
        void shouldReturnOnlySuccessorsWhenNoBranches() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .build();
            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            List<ExecutionNode> allChildren = node.getAllChildNodes();

            assertEquals(1, allChildren.size());
            assertEquals("mcp:next", allChildren.get(0).getNodeId());
        }

        @Test
        @DisplayName("When fork is skipped, branch targets should be accessible via getAllChildNodes for skip propagation")
        void whenForkSkippedBranchTargetsShouldBeAccessibleForSkipPropagation() {
            ExecutionNode taskA = createMockNode("mcp:task_a");
            ExecutionNode taskB = createMockNode("mcp:task_b");

            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Task A", new ArrayList<>(List.of(taskA)))
                .addBranch("branch_1", "Task B", new ArrayList<>(List.of(taskB)))
                .build();

            // getAllChildNodes provides the set used by default getSkippedChildNodes
            // When fork is skipped, all its children should be propagated for skipping
            List<ExecutionNode> allChildren = node.getAllChildNodes();
            assertFalse(allChildren.isEmpty());
            assertEquals(2, allChildren.size());

            // getSkippedChildNodes returns empty for fork (fork has no "skipped" branches),
            // but getAllChildNodes provides the traversal set for skip propagation at a higher level
            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());
            List<ExecutionNode> skipped = node.getSkippedChildNodes(result);
            assertTrue(skipped.isEmpty(), "Fork never skips its own branches");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:fork", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            ForkNode node = ForkNode.builder()
                .nodeId("core:fork")
                .addBranch("branch_0", "Branch A")
                .build();

            NodeExecutionResult result = NodeExecutionResult.failure("core:fork", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
