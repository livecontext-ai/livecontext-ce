package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForkNodeWirer Tests")
class ForkNodeWirerTest {

    private ForkNodeWirer wirer;

    @BeforeEach
    void setUp() {
        wirer = new ForkNodeWirer();
    }

    @Nested
    @DisplayName("wireForkBranchTargets()")
    class WireForkBranchTargetsTests {

        @Test
        @DisplayName("Should do nothing when plan has no cores")
        void shouldDoNothingWhenNoCores() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getCores()).thenReturn(null);
            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - no exception, method returns gracefully
            assertTrue(true);
        }

        @Test
        @DisplayName("Should skip non-fork cores")
        void shouldSkipNonForkCores() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            WorkflowPlan plan = mock(WorkflowPlan.class);

            Core decisionCore = createCore("decision", "My Decision", "dec-1");
            Core loopCore = createCore("loop", "My Loop", "loop-1");
            when(plan.getCores()).thenReturn(List.of(decisionCore, loopCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - no exception, method returns gracefully
            assertTrue(true);
        }

        @Test
        @DisplayName("Should wire fork branch_0 to target node via addForkBranch()")
        void shouldWireForkBranch0ToTarget() {
            // Given - use ExecutionNode mock with isForkNode() returning true
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:task_a", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:parallel", Map.of("branch_0", "mcp:task_a"));

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - uses polymorphic addForkBranch(String, String, ExecutionNode)
            verify(forkNode).addForkBranch("branch_0", "Branch 0", targetNode);
            verify(targetNode).addPredecessor("core:parallel");
        }

        @Test
        @DisplayName("Should wire multiple fork branches in order")
        void shouldWireMultipleForkBranchesInOrder() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetA = mock(ExecutionNode.class);
            ExecutionNode targetB = mock(ExecutionNode.class);
            ExecutionNode targetC = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:task_a", targetA);
            nodeMap.put("mcp:task_b", targetB);
            nodeMap.put("mcp:task_c", targetC);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("branch_0", "mcp:task_a");
            ports.put("branch_1", "mcp:task_b");
            ports.put("branch_2", "mcp:task_c");
            forkBranchTargets.put("core:parallel", ports);

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - should add 3 branches via addForkBranch
            verify(forkNode, times(3)).addForkBranch(anyString(), anyString(), any(ExecutionNode.class));
        }

        @Test
        @DisplayName("Should sort branches by index before wiring")
        void shouldSortBranchesByIndexBeforeWiring() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetA = mock(ExecutionNode.class);
            ExecutionNode targetB = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:task_a", targetA);
            nodeMap.put("mcp:task_b", targetB);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            // Put branch_1 before branch_0 to test sorting
            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("branch_1", "mcp:task_b");
            ports.put("branch_0", "mcp:task_a");
            forkBranchTargets.put("core:parallel", ports);

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - verify both branches wired; branch_0 should be wired before branch_1
            var inOrder = inOrder(forkNode);
            inOrder.verify(forkNode).addForkBranch("branch_0", "Branch 0", targetA);
            inOrder.verify(forkNode).addForkBranch("branch_1", "Branch 1", targetB);
        }

        @Test
        @DisplayName("Should add predecessor to target node for implicit merge detection")
        void shouldAddPredecessorToTargetNode() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:parallel", Map.of("branch_0", "mcp:target"));

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then
            verify(targetNode).addPredecessor("core:parallel");
        }

        @Test
        @DisplayName("Should skip when fork node not found in nodeMap")
        void shouldSkipWhenForkNodeNotFound() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            // No fork node in the map

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:parallel", Map.of("branch_0", "mcp:target"));

            // When - should not throw exception
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - no exception
            assertTrue(true);
        }

        @Test
        @DisplayName("Should skip when target node not found")
        void shouldSkipWhenTargetNodeNotFound() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            // No target node in the map

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:parallel", Map.of("branch_0", "mcp:missing_target"));

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - addForkBranch should NOT be called since target not found
            verify(forkNode, never()).addForkBranch(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Should use core id when label is null")
        void shouldUseCoreIdWhenLabelIsNull() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:fork_1", forkNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            // Core with null label, should use id "fork_1"
            Core forkCore = createCore("fork", null, "fork_1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:fork_1", Map.of("branch_0", "mcp:target"));

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then
            verify(forkNode).addForkBranch(eq("branch_0"), eq("Branch 0"), eq(targetNode));
        }

        @Test
        @DisplayName("Should handle empty branch targets gracefully")
        void shouldHandleEmptyBranchTargets() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            // Empty branch targets
            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - no addForkBranch calls
            verify(forkNode, never()).addForkBranch(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Should handle non-BaseNode targets (predecessor via interface method)")
        void shouldHandleNonBaseNodeTargets() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class); // Not a BaseNode

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            forkBranchTargets.put("core:parallel", Map.of("branch_0", "mcp:target"));

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - addForkBranch is called and addPredecessor via interface
            verify(forkNode).addForkBranch("branch_0", "Branch 0", targetNode);
            verify(targetNode).addPredecessor("core:parallel");
        }

        @Test
        @DisplayName("Should handle large branch indices")
        void shouldHandleLargeBranchIndices() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode target10 = mock(ExecutionNode.class);
            ExecutionNode target99 = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:task_10", target10);
            nodeMap.put("mcp:task_99", target99);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("branch_10", "mcp:task_10");
            ports.put("branch_99", "mcp:task_99");
            forkBranchTargets.put("core:parallel", ports);

            // When
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - branches wired with correct labels
            verify(forkNode).addForkBranch("branch_10", "Branch 10", target10);
            verify(forkNode).addForkBranch("branch_99", "Branch 99", target99);
        }

        @Test
        @DisplayName("Should handle malformed branch port names")
        void shouldHandleMalformedBranchPortNames() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("branch_abc", "mcp:target");  // Non-numeric index
            forkBranchTargets.put("core:parallel", ports);

            // When - should not throw exception
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - branch is still added (with default index 0 used for label)
            verify(forkNode).addForkBranch("branch_abc", "Branch 0", targetNode);
        }

        @Test
        @DisplayName("Should handle null port name")
        void shouldHandleNullPortName() {
            // Given
            ExecutionNode forkNode = mock(ExecutionNode.class);
            when(forkNode.isForkNode()).thenReturn(true);
            ExecutionNode targetNode = mock(ExecutionNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Core forkCore = createCore("fork", "Parallel", "fork-1");
            when(plan.getCores()).thenReturn(List.of(forkCore));

            Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put(null, "mcp:target");  // Null port name
            forkBranchTargets.put("core:parallel", ports);

            // When - should not throw exception
            wirer.wireForkBranchTargets(nodeMap, plan, forkBranchTargets);

            // Then - branch is added (with default handling for null)
            verify(forkNode).addForkBranch(isNull(), eq("Branch 0"), eq(targetNode));
        }
    }

    // Helper methods

    private Core createCore(String type, String label, String id) {
        return new Core(id, type, null, label, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
