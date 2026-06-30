package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionNodeWirer Tests")
class DecisionNodeWirerTest {

    private DecisionNodeWirer wirer;

    @BeforeEach
    void setUp() {
        wirer = new DecisionNodeWirer();
    }

    @Nested
    @DisplayName("wireDecisionBranchTargets()")
    class WireDecisionBranchTargetsTests {

        @Test
        @DisplayName("Should do nothing when plan has no cores")
        void shouldDoNothingWhenNoCores() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getCores()).thenReturn(null);
            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - no exception, method returns gracefully
            assertTrue(true);
        }

        @Test
        @DisplayName("Should skip non-decision cores")
        void shouldSkipNonDecisionCores() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            WorkflowPlan plan = mock(WorkflowPlan.class);

            Core loopCore = createCore("loop", "My Loop", "loop-1");
            when(plan.getCores()).thenReturn(List.of(loopCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - no exception, method returns gracefully
            assertTrue(true);
        }

        @Test
        @DisplayName("Should wire decision if branch to target node")
        void shouldWireDecisionIfBranchToTarget() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);
            StepNode targetNode = mock(StepNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check_status", decisionNode);
            nodeMap.put("mcp:success_step", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            Core decisionCore = createDecisionCore("decision", "Check Status", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:check_status", Map.of("if", "mcp:success_step"));

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - source uses addBranchTarget (polymorphic interface method)
            verify(decisionNode).addBranchTarget(0, targetNode);
        }

        @Test
        @DisplayName("Should wire decision else branch to target node")
        void shouldWireDecisionElseBranchToTarget() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);
            StepNode ifTarget = mock(StepNode.class);
            StepNode elseTarget = mock(StepNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);
            nodeMap.put("mcp:if_step", ifTarget);
            nodeMap.put("mcp:else_step", elseTarget);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10"),
                createDecisionCondition("else", null)
            );
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("if", "mcp:if_step");
            ports.put("else", "mcp:else_step");
            decisionPortTargets.put("core:check", ports);

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - source uses addBranchTarget (polymorphic interface method)
            verify(decisionNode).addBranchTarget(0, ifTarget);
            verify(decisionNode).addBranchTarget(1, elseTarget);
        }

        @Test
        @DisplayName("Should wire decision with elseif branches")
        void shouldWireDecisionWithElseifBranches() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);
            StepNode ifTarget = mock(StepNode.class);
            StepNode elseif0Target = mock(StepNode.class);
            StepNode elseif1Target = mock(StepNode.class);
            StepNode elseTarget = mock(StepNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:multi_check", decisionNode);
            nodeMap.put("mcp:if_step", ifTarget);
            nodeMap.put("mcp:elseif0_step", elseif0Target);
            nodeMap.put("mcp:elseif1_step", elseif1Target);
            nodeMap.put("mcp:else_step", elseTarget);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 100"),
                createDecisionCondition("elseif", "{{value}} > 50"),
                createDecisionCondition("elseif", "{{value}} > 10"),
                createDecisionCondition("else", null)
            );
            Core decisionCore = createDecisionCore("decision", "Multi Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("if", "mcp:if_step");
            ports.put("elseif_0", "mcp:elseif0_step");
            ports.put("elseif_1", "mcp:elseif1_step");
            ports.put("else", "mcp:else_step");
            decisionPortTargets.put("core:multi_check", ports);

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - source uses addBranchTarget (polymorphic interface method)
            verify(decisionNode).addBranchTarget(0, ifTarget);
            verify(decisionNode).addBranchTarget(1, elseif0Target);
            verify(decisionNode).addBranchTarget(2, elseif1Target);
            verify(decisionNode).addBranchTarget(3, elseTarget);
        }

        @Test
        @DisplayName("Should add predecessor to target node for implicit merge detection")
        void shouldAddPredecessorToTargetNode() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);
            BaseNode targetNode = mock(BaseNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:check", Map.of("if", "mcp:target"));

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - source adds predecessor WITH port: decisionKey + ":" + port
            verify(targetNode).addPredecessor("core:check:if");
        }

        @Test
        @DisplayName("Should skip switch type cores (only handles decision type)")
        void shouldSkipSwitchTypeCores() {
            // Given - DecisionNodeWirer only processes "decision" type cores
            DecisionNode switchNode = mock(DecisionNode.class);
            StepNode case0Target = mock(StepNode.class);
            StepNode defaultTarget = mock(StepNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:router", switchNode);
            nodeMap.put("mcp:case0_step", case0Target);
            nodeMap.put("mcp:default_step", defaultTarget);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("case", "value1"),
                createDecisionCondition("default", null)
            );
            Core switchCore = createDecisionCore("switch", "Router", "switch-1", conditions);
            when(plan.getCores()).thenReturn(List.of(switchCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            Map<String, String> ports = new HashMap<>();
            ports.put("if", "mcp:case0_step");
            ports.put("else", "mcp:default_step");
            decisionPortTargets.put("core:router", ports);

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - switch type is skipped by the wirer (only "decision" is processed)
            verify(switchNode, never()).addBranchTarget(anyInt(), any());
        }

        @Test
        @DisplayName("Should skip when decision node not found in nodeMap")
        void shouldSkipWhenDecisionNodeNotFound() {
            // Given
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            // No decision node in the map

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:check", Map.of("if", "mcp:target"));

            // When - should not throw exception
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - no exception
            assertTrue(true);
        }

        @Test
        @DisplayName("Should skip when target node not found")
        void shouldSkipWhenTargetNodeNotFound() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);
            // No target node in the map

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:check", Map.of("if", "mcp:missing_target"));

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - addBranchTarget should NOT be called since target not found
            verify(decisionNode, never()).addBranchTarget(anyInt(), any());
        }

        @Test
        @DisplayName("Should use core id when label is null")
        void shouldUseCoreIdWhenLabelIsNull() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);
            StepNode targetNode = mock(StepNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:decision_1", decisionNode);
            nodeMap.put("mcp:target", targetNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            // Core with null label, should use id "decision_1"
            Core decisionCore = createDecisionCore("decision", null, "decision_1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:decision_1", Map.of("if", "mcp:target"));

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - source uses addBranchTarget (polymorphic interface method)
            verify(decisionNode).addBranchTarget(0, targetNode);
        }

        @Test
        @DisplayName("Should handle empty port targets gracefully")
        void shouldHandleEmptyPortTargets() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            List<Core.DecisionCondition> conditions = List.of(
                createDecisionCondition("if", "{{value}} > 10")
            );
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", conditions);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            // Empty port targets
            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();

            // When
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then - no addBranchTarget calls
            verify(decisionNode, never()).addBranchTarget(anyInt(), any());
        }

        @Test
        @DisplayName("Should handle decision with null conditions")
        void shouldHandleDecisionWithNullConditions() {
            // Given
            DecisionNode decisionNode = mock(DecisionNode.class);
            when(decisionNode.isDecisionNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            // Core with null conditions
            Core decisionCore = createDecisionCore("decision", "Check", "dec-1", null);
            when(plan.getCores()).thenReturn(List.of(decisionCore));

            Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
            decisionPortTargets.put("core:check", Map.of("if", "mcp:target"));

            // When - should not throw exception
            wirer.wireDecisionBranchTargets(nodeMap, plan, decisionPortTargets);

            // Then
            verify(decisionNode, never()).addBranchTarget(anyInt(), any());
        }
    }

    // Helper methods

    private Core createCore(String type, String label, String id) {
        return new Core(id, type, null, label, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Core createDecisionCore(String type, String label, String id, List<Core.DecisionCondition> conditions) {
        return new Core(id, type, null, label, conditions, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Core.DecisionCondition createDecisionCondition(String type, String condition) {
        return new Core.DecisionCondition("cond_" + type, type, type, condition);
    }
}
