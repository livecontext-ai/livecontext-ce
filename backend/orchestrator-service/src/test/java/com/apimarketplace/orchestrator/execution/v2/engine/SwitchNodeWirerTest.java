package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
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
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Unit tests for SwitchNodeWirer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SwitchNodeWirer")
class SwitchNodeWirerTest {

    private SwitchNodeWirer wirer;

    @Mock private WorkflowPlan mockPlan;
    @Mock private ExecutionNode switchNode;
    @Mock private ExecutionNode targetNode1;
    @Mock private ExecutionNode targetNode2;
    @Mock private ExecutionNode defaultTarget;

    @BeforeEach
    void setUp() {
        wirer = new SwitchNodeWirer();
    }

    @Nested
    @DisplayName("wireSwitchCaseTargets")
    class WireSwitchCaseTargets {

        @Test
        @DisplayName("should return early when plan has null cores")
        void shouldReturnEarlyForNullCores() {
            when(mockPlan.getCores()).thenReturn(null);

            wirer.wireSwitchCaseTargets(Map.of(), mockPlan, Map.of());
            // No exception
        }

        @Test
        @DisplayName("should skip non-switch cores")
        void shouldSkipNonSwitchCores() {
            Core decisionCore = mock(Core.class);
            when(decisionCore.type()).thenReturn("decision");
            when(mockPlan.getCores()).thenReturn(List.of(decisionCore));

            wirer.wireSwitchCaseTargets(Map.of(), mockPlan, Map.of());
            verify(switchNode, never()).addBranchTarget(anyInt(), any());
        }

        @Test
        @DisplayName("should wire switch cases based on port targets")
        void shouldWireSwitchCases() {
            Core switchCore = mock(Core.class);
            when(switchCore.type()).thenReturn("switch");
            when(switchCore.label()).thenReturn("My Switch");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(switchCore.id()).thenReturn("switch-1");

            Core.SwitchCase case0 = mock(Core.SwitchCase.class);
            when(case0.type()).thenReturn("case");

            Core.SwitchCase case1 = mock(Core.SwitchCase.class);
            when(case1.type()).thenReturn("default");

            when(switchCore.switchCases()).thenReturn(List.of(case0, case1));
            when(mockPlan.getCores()).thenReturn(List.of(switchCore));
            when(switchNode.isSwitchNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:my_switch", switchNode);
            nodeMap.put("mcp:case_target", targetNode1);
            nodeMap.put("mcp:default_target", targetNode2);

            Map<String, String> portTargets = new HashMap<>();
            portTargets.put("case_0", "mcp:case_target");
            portTargets.put("default", "mcp:default_target");

            Map<String, Map<String, String>> switchPortTargets = Map.of("core:my_switch", portTargets);

            wirer.wireSwitchCaseTargets(nodeMap, mockPlan, switchPortTargets);

            verify(switchNode).addBranchTarget(0, targetNode1);
            verify(switchNode).addBranchTarget(1, targetNode2);
            // Predecessor must include the port suffix so SplitAwareNodeExecutor can
            // filter items by selected_branch. Matches DecisionNodeWirer / ApprovalNodeWirer.
            verify(targetNode1).addPredecessor("core:my_switch:case_0");
            verify(targetNode2).addPredecessor("core:my_switch:default");
        }

        @Test
        @DisplayName("should skip when switch node not found in nodeMap")
        void shouldSkipWhenNodeNotInMap() {
            Core switchCore = mock(Core.class);
            when(switchCore.type()).thenReturn("switch");
            when(switchCore.label()).thenReturn("Missing Switch");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(switchCore.id()).thenReturn("sw-1");
            when(mockPlan.getCores()).thenReturn(List.of(switchCore));

            wirer.wireSwitchCaseTargets(Map.of(), mockPlan, Map.of());
            // No exception, no interactions with switch node
        }

        @Test
        @DisplayName("should wire dynamic default when not handled by switchCases")
        void shouldWireDynamicDefault() {
            Core switchCore = mock(Core.class);
            when(switchCore.type()).thenReturn("switch");
            when(switchCore.label()).thenReturn("Switch No Default");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(switchCore.id()).thenReturn("sw-2");

            Core.SwitchCase case0 = mock(Core.SwitchCase.class);
            when(case0.type()).thenReturn("case");

            when(switchCore.switchCases()).thenReturn(List.of(case0));
            when(mockPlan.getCores()).thenReturn(List.of(switchCore));
            when(switchNode.isSwitchNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:switch_no_default", switchNode);
            nodeMap.put("mcp:case_target", targetNode1);
            nodeMap.put("mcp:default_target", defaultTarget);

            Map<String, String> portTargets = new HashMap<>();
            portTargets.put("case_0", "mcp:case_target");
            portTargets.put("default", "mcp:default_target");

            Map<String, Map<String, String>> switchPortTargets = Map.of("core:switch_no_default", portTargets);

            wirer.wireSwitchCaseTargets(nodeMap, mockPlan, switchPortTargets);

            // Should wire case_0 at index 0 and dynamic default at index 1 (cases.size())
            verify(switchNode).addBranchTarget(0, targetNode1);
            verify(switchNode).addBranchTarget(1, defaultTarget);
            // Both predecessors must carry port suffix for split-aware filtering.
            verify(targetNode1).addPredecessor("core:switch_no_default:case_0");
            verify(defaultTarget).addPredecessor("core:switch_no_default:default");
        }
    }
}
