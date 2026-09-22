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
    @DisplayName("createInterfaceNodes() - PDF options threaded to the node")
    class CreateInterfaceNodesTests {

        @Test
        @DisplayName("generatePdf + pdfFormat + pdfLandscape from the plan reach the InterfaceNode (no LLM in the loop)")
        void interfaceDefPdfFieldsReachTheNode() {
            // Full 12-arg InterfaceDef: generatePdf=true, format=Legal, landscape=true.
            InterfaceDef def = new InterfaceDef(
                "11111111-2222-3333-4444-555555555555", "My Form",
                Map.of(), Map.of(), true, Map.of(),
                /* isEntryInterface */ false, /* generateScreenshot */ false,
                /* exposeRenderedSource */ false, /* generatePdf */ true,
                /* pdfFormat */ "Legal", /* pdfLandscape */ true);

            WorkflowPlan plan = org.mockito.Mockito.mock(WorkflowPlan.class);
            when(plan.getInterfaces()).thenReturn(List.of(def));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createInterfaceNodes(nodeMap, plan);

            ExecutionNode node = nodeMap.get("interface:my_form");
            assertNotNull(node, "interface node must be registered under its normalized key");
            assertInstanceOf(InterfaceNode.class, node);
            InterfaceNode iface = (InterfaceNode) node;
            assertTrue(iface.isGeneratePdf(), "generatePdf must be threaded from InterfaceDef to the node");
            assertEquals("Legal", iface.getPdfFormat());
            assertTrue(iface.isPdfLandscape());
            // sanity: unrelated screenshot toggle stays off
            assertFalse(iface.isGenerateScreenshot());
        }

        @Test
        @DisplayName("the variable mapping from the plan reaches the InterfaceNode, which is the only place it can report it from")
        void interfaceDefVariableMappingReachesTheNode() {
            // The node never resolves these to render - the render API does, per viewer -
            // so nothing else would have caught the mapping not being threaded here, and
            // the node's own tests would have stayed green over an empty report in prod.
            Map<String, String> mapping = Map.of("rows", "{{core:fetch.output.items}}");
            InterfaceDef def = new InterfaceDef(
                "11111111-2222-3333-4444-555555555555", "Listing Page",
                Map.of(), mapping, true, Map.of(),
                /* isEntryInterface */ false, /* generateScreenshot */ false);

            WorkflowPlan plan = org.mockito.Mockito.mock(WorkflowPlan.class);
            when(plan.getInterfaces()).thenReturn(List.of(def));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createInterfaceNodes(nodeMap, plan);

            InterfaceNode iface = (InterfaceNode) nodeMap.get("interface:listing_page");
            assertNotNull(iface, "interface node must be registered under its normalized key");
            assertEquals(mapping, iface.getVariableMapping());
        }

        @Test
        @DisplayName("generateVideo + videoPreset + videoMaxDurationSeconds from the plan reach the InterfaceNode")
        void interfaceDefVideoFieldsReachTheNode() {
            InterfaceDef def = new InterfaceDef(
                "11111111-2222-3333-4444-555555555555", "Clip Card",
                Map.of(), Map.of(), true, Map.of(),
                /* isEntryInterface */ false, /* generateScreenshot */ false,
                /* exposeRenderedSource */ false, /* generatePdf */ false,
                /* pdfFormat */ null, /* pdfLandscape */ false,
                /* generateVideo */ true, /* videoPreset */ "square",
                /* videoMaxDurationSeconds */ 45);

            WorkflowPlan plan = org.mockito.Mockito.mock(WorkflowPlan.class);
            when(plan.getInterfaces()).thenReturn(List.of(def));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createInterfaceNodes(nodeMap, plan);

            ExecutionNode node = nodeMap.get("interface:clip_card");
            assertNotNull(node, "interface node must be registered under its normalized key");
            assertInstanceOf(InterfaceNode.class, node);
            InterfaceNode iface = (InterfaceNode) node;
            assertTrue(iface.isGenerateVideo(), "generateVideo must be threaded from InterfaceDef to the node");
            assertEquals("square", iface.getVideoPreset());
            assertEquals(45, iface.getVideoMaxDurationSeconds());
            // sanity: unrelated toggles stay off
            assertFalse(iface.isGenerateScreenshot());
            assertFalse(iface.isGeneratePdf());
        }

        @Test
        @DisplayName("12-arg InterfaceDef (pre-video plans) leaves video OFF on the node (back-compat)")
        void preVideoInterfaceDefDefaultsVideoOff() {
            InterfaceDef def = new InterfaceDef(
                "11111111-2222-3333-4444-555555555555", "Legacy Form",
                Map.of(), Map.of(), true, Map.of(),
                false, false, false, false, null, false);

            WorkflowPlan plan = org.mockito.Mockito.mock(WorkflowPlan.class);
            when(plan.getInterfaces()).thenReturn(List.of(def));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createInterfaceNodes(nodeMap, plan);

            InterfaceNode iface = (InterfaceNode) nodeMap.get("interface:legacy_form");
            assertNotNull(iface);
            assertFalse(iface.isGenerateVideo());
            assertNull(iface.getVideoPreset());
            assertNull(iface.getVideoMaxDurationSeconds());
        }

        @Test
        @DisplayName("an interface node is built with no display format (it lives on the interface)")
        void interfaceNodeCarriesNoFormat() {
            // The plan no longer carries a shape for the node, and the node exposes no getter
            // for one: the screenshot/video path resolves it from the interface itself. This
            // pins that the factory still builds a working node without it.
            InterfaceDef def = new InterfaceDef(
                "11111111-2222-3333-4444-555555555555", "Format Card",
                Map.of(), Map.of(), true, Map.of(),
                /* isEntryInterface */ false, /* generateScreenshot */ true,
                /* exposeRenderedSource */ false, /* generatePdf */ false,
                /* pdfFormat */ null, /* pdfLandscape */ false,
                /* generateVideo */ false, /* videoPreset */ null,
                /* videoMaxDurationSeconds */ null, /* videoMode */ null,
                /* videoFps */ null);

            WorkflowPlan plan = org.mockito.Mockito.mock(WorkflowPlan.class);
            when(plan.getInterfaces()).thenReturn(List.of(def));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createInterfaceNodes(nodeMap, plan);

            InterfaceNode iface = (InterfaceNode) nodeMap.get("interface:format_card");
            assertNotNull(iface, "interface node must be registered under its normalized key");
            assertTrue(iface.isGenerateScreenshot());
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

    /**
     * Generate is built HERE, from the plan's agents, and not by the core
     * builder.
     *
     * <p>It belongs to the AI family: keyed {@code agent:<label>} and filed
     * under {@code agents[]}, so its output is referenced
     * {@code {{agent:make_clip.output.file}}}. A plan that still files it under
     * {@code cores[]} builds no node at all, and the run then reports a step
     * that never existed rather than an error.
     */
    @Nested
    @DisplayName("createAgentNodes() - generate")
    class CreateGenerateNodesTests {

        private WorkflowPlan planWithGenerateAgent(String label, Map<String, Object> params) {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("id", "g1");
            agentData.put("type", "generate");
            agentData.put("label", label);
            if (params != null) {
                agentData.put("params", params);
            }
            data.put("agents", List.of(agentData));
            return WorkflowPlan.fromMap(data);
        }

        /**
         * Two KEYS, one NODE, and the difference matters downstream.
         *
         * <p>A label that does not normalize to itself is registered twice: once
         * under the normalized key and once under the raw lowercased label, so an
         * edge written either way resolves. Both entries hold the SAME instance.
         *
         * <p>Worth stating explicitly because a caller that counts the map to
         * decide how many nodes were built gets the wrong answer. That is exactly
         * what the standalone-node probe did, and it refused every generate node
         * whose label had a space in it while reporting a fan-out that had not
         * happened.
         */
        @Test
        @DisplayName("Should register a spaced label under two keys that hold ONE node")
        void shouldRegisterTwoKeysForOneNode() {
            WorkflowPlan plan = planWithGenerateAgent("Make Clip",
                Map.of("model", "seedance-2.0-fast"));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertEquals(2, nodeMap.size(), "the alias is registered as well as the key");
            assertSame(nodeMap.get("agent:make_clip"), nodeMap.get("agent:make clip"),
                "the alias must point at the SAME node, so counting keys counts nodes wrong");
        }

        /**
         * And a label that already normalizes to itself gets ONE key, which is
         * why the defect above only ever showed on a multi-word label.
         */
        @Test
        @DisplayName("Should register a single key when the label already normalizes to itself")
        void shouldRegisterOneKeyForASimpleLabel() {
            WorkflowPlan plan = planWithGenerateAgent("generate",
                Map.of("model", "seedance-2.0-fast"));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertEquals(1, nodeMap.size());
        }

        @Test
        @DisplayName("Should create generate node under the agent key, carrying the FULL params map verbatim")
        void shouldCreateGenerateNodeWithParamsMap() {
            Map<String, Object> params = Map.of(
                "model", "seedance-2.0-fast",
                "prompt", "a paper boat in a rain gutter",
                "duration_seconds", 5,
                "credential_source", "platform");
            WorkflowPlan plan = planWithGenerateAgent("Make Clip", params);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            String agentKey = "agent:make_clip";
            assertTrue(nodeMap.containsKey(agentKey));
            // The key it must NOT carry, derived at RUN TIME from the one it must.
            // Written as "core:" + "make_clip" the compiler folds the two halves into
            // one constant, so the trick bought nothing: a repo-wide rename of the old
            // spelling would have rewritten it just as readily and left this asserting
            // that the CORRECT key is absent.
            String forbiddenKey = "core:" + agentKey.substring(agentKey.indexOf(':') + 1);
            assertFalse(nodeMap.containsKey(forbiddenKey),
                "a core: key addresses nothing and resolves to an empty string");
            assertInstanceOf(GenerateNode.class, nodeMap.get("agent:make_clip"));
            GenerateNode node = (GenerateNode) nodeMap.get("agent:make_clip");
            assertEquals("seedance-2.0-fast", node.getParams().get("model"));
            // Numbers must survive the plan round trip: a stringified duration would
            // change what the platform bills the run on.
            assertEquals(5, node.getParams().get("duration_seconds"));
            assertEquals("platform", node.getParams().get("credential_source"));
        }

        @Test
        @DisplayName("Should create node with empty params when the params map is absent (fails at runtime, not build time)")
        void shouldCreateNodeWithEmptyParamsWhenAbsent() {
            WorkflowPlan plan = planWithGenerateAgent("Make Clip", null);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("agent:make_clip"));
            GenerateNode node = (GenerateNode) nodeMap.get("agent:make_clip");
            assertTrue(node.getParams().isEmpty());
        }

        @Test
        @DisplayName("Should leave the other AI types alone: only type=generate builds a GenerateNode")
        void shouldOnlyBuildGenerateForItsOwnType() {
            WorkflowPlan plan = createPlanWithAgent("Data Analyzer");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            factory.createAgentNodes(nodeMap, plan);

            assertInstanceOf(AgentNode.class, nodeMap.get("agent:data_analyzer"));
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
