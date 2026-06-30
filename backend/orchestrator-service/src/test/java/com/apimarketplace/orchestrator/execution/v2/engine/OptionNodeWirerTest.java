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
 * Unit tests for OptionNodeWirer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OptionNodeWirer")
class OptionNodeWirerTest {

    private OptionNodeWirer wirer;

    @Mock private WorkflowPlan mockPlan;
    @Mock private ExecutionNode optionNode;
    @Mock private ExecutionNode targetNode1;
    @Mock private ExecutionNode targetNode2;

    @BeforeEach
    void setUp() {
        wirer = new OptionNodeWirer();
    }

    @Nested
    @DisplayName("wireOptionChoiceTargets")
    class WireOptionChoiceTargets {

        @Test
        @DisplayName("should return early when plan has null cores")
        void shouldReturnEarlyForNullCores() {
            when(mockPlan.getCores()).thenReturn(null);

            wirer.wireOptionChoiceTargets(Map.of(), mockPlan, Map.of());
            // No exception
        }

        @Test
        @DisplayName("should skip non-option cores")
        void shouldSkipNonOptionCores() {
            Core decisionCore = mock(Core.class);
            when(decisionCore.type()).thenReturn("decision");
            when(mockPlan.getCores()).thenReturn(List.of(decisionCore));

            wirer.wireOptionChoiceTargets(Map.of(), mockPlan, Map.of());
            verify(optionNode, never()).addBranchTarget(anyInt(), any());
        }

        @Test
        @DisplayName("should wire option choices based on port targets")
        void shouldWireOptionChoices() {
            Core optionCore = mock(Core.class);
            when(optionCore.type()).thenReturn("option");
            when(optionCore.label()).thenReturn("My Option");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(optionCore.id()).thenReturn("opt-1");

            Core.OptionChoice choice0 = mock(Core.OptionChoice.class);
            Core.OptionChoice choice1 = mock(Core.OptionChoice.class);
            when(optionCore.optionChoices()).thenReturn(List.of(choice0, choice1));

            when(mockPlan.getCores()).thenReturn(List.of(optionCore));
            when(optionNode.isOptionNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:my_option", optionNode);
            nodeMap.put("mcp:choice_a", targetNode1);
            nodeMap.put("mcp:choice_b", targetNode2);

            Map<String, String> portTargets = new HashMap<>();
            portTargets.put("choice_0", "mcp:choice_a");
            portTargets.put("choice_1", "mcp:choice_b");

            Map<String, Map<String, String>> optionPortTargets = Map.of("core:my_option", portTargets);

            wirer.wireOptionChoiceTargets(nodeMap, mockPlan, optionPortTargets);

            verify(optionNode).addBranchTarget(0, targetNode1);
            verify(optionNode).addBranchTarget(1, targetNode2);
            // Predecessor must include the port suffix so SplitAwareNodeExecutor can
            // filter items by selected_branch. Matches DecisionNodeWirer / ApprovalNodeWirer.
            verify(targetNode1).addPredecessor("core:my_option:choice_0");
            verify(targetNode2).addPredecessor("core:my_option:choice_1");
        }

        @Test
        @DisplayName("should skip when option node not in map")
        void shouldSkipWhenNotInMap() {
            Core optionCore = mock(Core.class);
            when(optionCore.type()).thenReturn("option");
            when(optionCore.label()).thenReturn("Missing");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(optionCore.id()).thenReturn("opt-1");
            when(mockPlan.getCores()).thenReturn(List.of(optionCore));

            wirer.wireOptionChoiceTargets(Map.of(), mockPlan, Map.of());
            // No exception
        }

        @Test
        @DisplayName("should skip when target node not found")
        void shouldSkipWhenTargetNotFound() {
            Core optionCore = mock(Core.class);
            when(optionCore.type()).thenReturn("option");
            when(optionCore.label()).thenReturn("Option");
            // id() is only used as fallback when label() is null, use lenient
            lenient().when(optionCore.id()).thenReturn("opt-1");

            Core.OptionChoice choice0 = mock(Core.OptionChoice.class);
            when(optionCore.optionChoices()).thenReturn(List.of(choice0));

            when(mockPlan.getCores()).thenReturn(List.of(optionCore));
            when(optionNode.isOptionNode()).thenReturn(true);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:option", optionNode);

            Map<String, String> portTargets = Map.of("choice_0", "mcp:nonexistent");
            Map<String, Map<String, String>> optionPortTargets = Map.of("core:option", portTargets);

            wirer.wireOptionChoiceTargets(nodeMap, mockPlan, optionPortTargets);

            verify(optionNode, never()).addBranchTarget(anyInt(), any());
        }
    }
}
