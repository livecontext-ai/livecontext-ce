package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.services.TemplateEngine;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExecutionNodeFactory.
 *
 * ExecutionNodeFactory creates basic ExecutionNodes from WorkflowPlan elements:
 * - Trigger nodes
 * - Step nodes
 * - Agent nodes
 * - Split nodes
 * - End node
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionNodeFactory")
class ExecutionNodeFactoryTest {

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private com.apimarketplace.orchestrator.services.agent.AgentConfigResolver agentConfigResolver;

    private ExecutionNodeFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExecutionNodeFactory(templateEngine, agentConfigResolver);
    }

    @Nested
    @DisplayName("createBasicNodes()")
    class CreateBasicNodesTests {

        @Test
        @DisplayName("Should create all basic node types")
        void shouldCreateAllBasicNodeTypes() {
            WorkflowPlan plan = createFullPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createBasicNodes(nodeMap, plan);

            // Should have trigger, steps, agents, and end node
            assertTrue(nodeMap.containsKey("trigger:start"));
            assertTrue(nodeMap.containsKey("mcp:api_call"));
            assertTrue(nodeMap.containsKey("agent:analyzer"));
            assertTrue(nodeMap.containsKey("__end__"));
        }
    }

    @Nested
    @DisplayName("createTriggerNodes()")
    class CreateTriggerNodesTests {

        @Test
        @DisplayName("Should create trigger node from plan")
        void shouldCreateTriggerNodeFromPlan() {
            WorkflowPlan plan = createPlanWithTrigger("Start", "webhook");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createTriggerNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("trigger:start"));
            assertInstanceOf(TriggerNode.class, nodeMap.get("trigger:start"));
        }

        @Test
        @DisplayName("Should normalize trigger label to key")
        void shouldNormalizeTriggerLabelToKey() {
            WorkflowPlan plan = createPlanWithTrigger("My Webhook Trigger", "webhook");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createTriggerNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("trigger:my_webhook_trigger"));
        }

        @Test
        @DisplayName("Should handle null triggers list")
        void shouldHandleNullTriggersList() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> factory.createTriggerNodes(nodeMap, plan));
            assertFalse(nodeMap.keySet().stream().anyMatch(k -> k.startsWith("trigger:")));
        }

        @Test
        @DisplayName("Should handle empty triggers list")
        void shouldHandleEmptyTriggersList() {
            Map<String, Object> data = new HashMap<>();
            data.put("id", "test");
            data.put("tenant_id", "t1");
            data.put("triggers", List.of());
            data.put("mcps", List.of());
            data.put("edges", List.of());
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createTriggerNodes(nodeMap, plan);

            assertFalse(nodeMap.keySet().stream().anyMatch(k -> k.startsWith("trigger:")));
        }
    }

    @Nested
    @DisplayName("createStepNodes()")
    class CreateStepNodesTests {

        @Test
        @DisplayName("Should create step node from plan")
        void shouldCreateStepNodeFromPlan() {
            WorkflowPlan plan = createPlanWithStep("API Call", "call_api");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createStepNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("mcp:api_call"));
            assertInstanceOf(StepNode.class, nodeMap.get("mcp:api_call"));
        }

        @Test
        @DisplayName("Should normalize step label to key")
        void shouldNormalizeStepLabelToKey() {
            WorkflowPlan plan = createPlanWithStep("Fetch User Data", "fetch_user");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createStepNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("mcp:fetch_user_data"));
        }

        @Test
        @DisplayName("Should add alias for step id")
        void shouldAddAliasForStepId() {
            WorkflowPlan plan = createPlanWithStep("My Step", "step_123");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createStepNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("mcp:my_step"));
            assertTrue(nodeMap.containsKey("mcp:step_123"));
            assertSame(nodeMap.get("mcp:my_step"), nodeMap.get("mcp:step_123"));
        }

        @Test
        @DisplayName("Should handle multiple steps")
        void shouldHandleMultipleSteps() {
            Map<String, Object> data = createBasePlanData();
            data.put("mcps", List.of(
                Map.of("id", "s1", "label", "Step One"),
                Map.of("id", "s2", "label", "Step Two"),
                Map.of("id", "s3", "label", "Step Three")
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createStepNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("mcp:step_one"));
            assertTrue(nodeMap.containsKey("mcp:step_two"));
            assertTrue(nodeMap.containsKey("mcp:step_three"));
        }

        @Test
        @DisplayName("Should handle null mcps list")
        void shouldHandleNullMcpsList() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> factory.createStepNodes(nodeMap, plan));
        }
    }

    @Nested
    @DisplayName("createAgentNodes()")
    class CreateAgentNodesTests {

        @Test
        @DisplayName("Should create agent node from plan")
        void shouldCreateAgentNodeFromPlan() {
            WorkflowPlan plan = createPlanWithAgent("Data Analyzer");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("agent:data_analyzer"));
            assertInstanceOf(AgentNode.class, nodeMap.get("agent:data_analyzer"));
        }

        @Test
        @DisplayName("Should add alias for raw label")
        void shouldAddAliasForRawLabel() {
            WorkflowPlan plan = createPlanWithAgent("My Agent");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("agent:my_agent"));
            assertTrue(nodeMap.containsKey("agent:my agent"));
            assertSame(nodeMap.get("agent:my_agent"), nodeMap.get("agent:my agent"));
        }

        @Test
        @DisplayName("Should handle null agents list")
        void shouldHandleNullAgentsList() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> factory.createAgentNodes(nodeMap, plan));
        }

        @Test
        @DisplayName("Should forward organization scope when resolving entity-backed agents")
        void shouldForwardOrganizationScopeForEntityBackedAgents() {
            WorkflowPlan plan = createPlanWithAgentConfig("Org Agent", UUID.randomUUID().toString());
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            when(agentConfigResolver.resolve(
                any(Agent.class),
                eq("tenant-1"),
                eq("org-1")
            )).thenAnswer(invocation -> new com.apimarketplace.orchestrator.services.agent.AgentConfigResolver.ResolveResult(
                invocation.getArgument(0),
                com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides.EMPTY));

            factory.createAgentNodes(nodeMap, plan, "tenant-1", "org-1");

            verify(agentConfigResolver).resolve(any(Agent.class), eq("tenant-1"), eq("org-1"));
            assertTrue(nodeMap.containsKey("agent:org_agent"));
        }
    }

    @Nested
    @DisplayName("createEndNode()")
    class CreateEndNodeTests {

        @Test
        @DisplayName("Should create end node with __end__ key")
        void shouldCreateEndNodeWithEndKey() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createEndNode(nodeMap);

            assertTrue(nodeMap.containsKey("__end__"));
            assertInstanceOf(EndNode.class, nodeMap.get("__end__"));
        }

        @Test
        @DisplayName("End node should have correct id")
        void endNodeShouldHaveCorrectId() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createEndNode(nodeMap);

            EndNode endNode = (EndNode) nodeMap.get("__end__");
            assertEquals("__end__", endNode.getNodeId());
        }
    }

    @Nested
    @DisplayName("createSplitNodes()")
    class CreateSplitNodesTests {

        @Test
        @DisplayName("Should handle plan with no split nodes")
        void shouldHandlePlanWithNoSplitNodes() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> factory.createSplitNodes(nodeMap, plan));
        }
    }

    // ===== Helper methods =====

    private Map<String, Object> createBasePlanData() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of());
        data.put("mcps", List.of());
        data.put("edges", List.of());
        return data;
    }

    private WorkflowPlan createEmptyPlan() {
        return WorkflowPlan.fromMap(createBasePlanData());
    }

    private WorkflowPlan createPlanWithTrigger(String label, String type) {
        Map<String, Object> data = createBasePlanData();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", label, "type", type, "strategy", "single")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithStep(String label, String id) {
        Map<String, Object> data = createBasePlanData();
        data.put("mcps", List.of(
            Map.of("id", id, "label", label)
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithAgent(String label) {
        Map<String, Object> data = createBasePlanData();
        data.put("agents", List.of(
            Map.of("id", "a1", "label", label, "type", "agent")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithAgentConfig(String label, String agentConfigId) {
        Map<String, Object> data = createBasePlanData();
        data.put("agents", List.of(
            Map.of("id", "a1", "label", label, "type", "agent", "agentConfigId", agentConfigId)
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createFullPlan() {
        Map<String, Object> data = createBasePlanData();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "API Call")
        ));
        data.put("agents", List.of(
            Map.of("id", "a1", "label", "Analyzer", "type", "agent")
        ));
        return WorkflowPlan.fromMap(data);
    }
}
