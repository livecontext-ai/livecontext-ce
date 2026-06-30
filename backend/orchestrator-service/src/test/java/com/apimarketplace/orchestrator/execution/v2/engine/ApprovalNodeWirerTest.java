package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Unit tests for ApprovalNodeWirer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalNodeWirer")
class ApprovalNodeWirerTest {

    private ApprovalNodeWirer wirer;

    @Mock private WorkflowPlan mockPlan;
    @Mock private ExecutionNode approvalNode;
    @Mock private ExecutionNode targetNode1;
    @Mock private ExecutionNode targetNode2;

    @BeforeEach
    void setUp() {
        wirer = new ApprovalNodeWirer();
    }

    @Nested
    @DisplayName("wireApprovalPortTargets")
    class WireApprovalPortTargets {

        @Test
        @DisplayName("should wire approval ports to target nodes")
        void shouldWireApprovalPorts() {
            when(approvalNode.isApprovalNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:approval_1", approvalNode);
            nodeMap.put("mcp:step_approved", targetNode1);
            nodeMap.put("mcp:step_rejected", targetNode2);

            Map<String, String> portTargets = new HashMap<>();
            portTargets.put("approved", "mcp:step_approved");
            portTargets.put("rejected", "mcp:step_rejected");

            Map<String, Map<String, String>> approvalPortTargets = new HashMap<>();
            approvalPortTargets.put("core:approval_1", portTargets);

            wirer.wireApprovalPortTargets(nodeMap, mockPlan, approvalPortTargets);

            verify(approvalNode).addPortTarget("approved", targetNode1);
            verify(approvalNode).addPortTarget("rejected", targetNode2);
            verify(targetNode1).addPredecessor("core:approval_1:approved");
            verify(targetNode2).addPredecessor("core:approval_1:rejected");
        }

        @Test
        @DisplayName("should skip when approval node not found in map")
        void shouldSkipWhenNodeNotFound() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            Map<String, String> portTargets = Map.of("approved", "mcp:step_1");
            Map<String, Map<String, String>> approvalPortTargets = Map.of("core:missing", portTargets);

            wirer.wireApprovalPortTargets(nodeMap, mockPlan, approvalPortTargets);

            verifyNoInteractions(targetNode1);
        }

        @Test
        @DisplayName("should skip when node is not an approval node")
        void shouldSkipNonApprovalNode() {
            when(approvalNode.isApprovalNode()).thenReturn(false);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:not_approval", approvalNode);

            Map<String, String> portTargets = Map.of("approved", "mcp:step_1");
            Map<String, Map<String, String>> approvalPortTargets = Map.of("core:not_approval", portTargets);

            wirer.wireApprovalPortTargets(nodeMap, mockPlan, approvalPortTargets);

            verify(approvalNode, never()).addPortTarget(anyString(), any());
        }

        @Test
        @DisplayName("should skip when target node not found")
        void shouldSkipWhenTargetNotFound() {
            when(approvalNode.isApprovalNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:approval_1", approvalNode);

            Map<String, String> portTargets = Map.of("approved", "mcp:missing_target");
            Map<String, Map<String, String>> approvalPortTargets = Map.of("core:approval_1", portTargets);

            wirer.wireApprovalPortTargets(nodeMap, mockPlan, approvalPortTargets);

            verify(approvalNode, never()).addPortTarget(anyString(), any());
        }

        @Test
        @DisplayName("should handle empty port targets")
        void shouldHandleEmptyPortTargets() {
            wirer.wireApprovalPortTargets(Map.of(), mockPlan, Map.of());
            // no exceptions
        }
    }
}
