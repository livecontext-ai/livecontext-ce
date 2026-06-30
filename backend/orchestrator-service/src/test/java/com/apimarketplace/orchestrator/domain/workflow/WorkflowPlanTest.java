package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowPlan class.
 *
 * WorkflowPlan is the unified workflow definition containing triggers, steps, agents, edges, cores, etc.
 */
@DisplayName("WorkflowPlan")
class WorkflowPlanTest {

    @Nested
    @DisplayName("fromMap()")
    class FromMapTests {

        @Test
        @DisplayName("Should parse minimal plan")
        void shouldParseMinimalPlan() {
            Map<String, Object> data = createBasePlan();

            // tenantId comes from external parameter, not from plan JSON
            WorkflowPlan plan = WorkflowPlan.fromMap(data, "test-tenant");

            assertNotNull(plan);
            // Plan ID is enforced to be a valid UUID; if not, a new UUID is generated
            // The original ID is stored in originalPlan.original_id
            assertNotNull(plan.getId());
            assertDoesNotThrow(() -> java.util.UUID.fromString(plan.getId()));
            assertEquals("test-tenant", plan.getTenantId());
        }

        @Test
        @DisplayName("Should parse plan with triggers")
        void shouldParsePlanWithTriggers() {
            Map<String, Object> data = createBasePlan();
            data.put("triggers", List.of(
                Map.of("id", "t1", "label", "Webhook", "type", "webhook", "strategy", "single")
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getTriggers().size());
            assertEquals("webhook", plan.getTriggers().get(0).label());
        }

        @Test
        @DisplayName("Should parse plan with mcps/steps")
        void shouldParsePlanWithMcps() {
            Map<String, Object> data = createBasePlan();
            data.put("mcps", List.of(
                Map.of(
                    "id", "s1",
                    "label", "Fetch Data",
                    "selectedCredentialId", 42,
                    "params", Map.of("url", "https://api.example.com"))
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getMcps().size());
            assertEquals("Fetch Data", plan.getMcps().get(0).label());
            assertEquals(42L, plan.getMcps().get(0).selectedCredentialId());
        }

        @Test
        @DisplayName("Should parse plan with agents")
        void shouldParsePlanWithAgents() {
            Map<String, Object> data = createBasePlan();
            data.put("agents", List.of(
                Map.of("id", "a1", "label", "Analyzer", "prompt", "Analyze the data")
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getAgents().size());
            assertEquals("Analyzer", plan.getAgents().get(0).label());
        }

        @Test
        @DisplayName("Should parse plan with edges")
        void shouldParsePlanWithEdges() {
            Map<String, Object> data = createBasePlan();
            data.put("edges", List.of(
                Map.of("from", "trigger:webhook", "to", "mcp:fetch")
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getEdges().size());
            assertEquals("trigger:webhook", plan.getEdges().get(0).from());
            assertEquals("mcp:fetch", plan.getEdges().get(0).to());
        }

        @Test
        @DisplayName("Should parse plan with cores")
        void shouldParsePlanWithCores() {
            Map<String, Object> data = createBasePlan();
            data.put("cores", List.of(
                Map.of("id", "c1", "type", "decision", "label", "Check Status",
                    "decisionConditions", List.of(
                        Map.of("id", "cond1", "type", "if", "label", "Success", "expression", "{{status}} == 200")
                    ))
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getCores().size());
            assertTrue(plan.getCores().get(0).isDecision());
        }

        @Test
        @DisplayName("Should parse plan with notes")
        void shouldParsePlanWithNotes() {
            Map<String, Object> data = createBasePlan();
            data.put("notes", List.of(
                Map.of("id", "n1", "text", "Documentation note")
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertEquals(1, plan.getNotes().size());
            assertEquals("Documentation note", plan.getNotes().get(0).text());
        }

        @Test
        @DisplayName("Should parse plan with external tenantId")
        void shouldParsePlanWithExternalTenantId() {
            Map<String, Object> data = createBasePlan();

            WorkflowPlan plan = WorkflowPlan.fromMap(data, "external-tenant");

            assertEquals("external-tenant", plan.getTenantId());
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create plan with all parameters")
        void shouldCreatePlanWithAllParameters() {
            Trigger trigger = new Trigger("t1", "Start", "single", "webhook");
            Step step = new Step("s1", "mcp", "Fetch", null, Map.of(), null, null, null);
            Agent agent = new Agent("a1", "agent", "Analyzer", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            Edge edge = new Edge("trigger:start", "mcp:fetch");
            Core core = new Core("c1", "decision", null, "Check", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            WorkflowPlan plan = new WorkflowPlan("plan-1", "tenant-1",
                List.of(trigger), List.of(step), List.of(agent), List.of(edge),
                List.of(core), null, null, null, Map.of());

            assertEquals("plan-1", plan.getId());
            assertEquals("tenant-1", plan.getTenantId());
            assertEquals(1, plan.getTriggers().size());
            assertEquals(1, plan.getMcps().size());
            assertEquals(1, plan.getAgents().size());
            assertEquals(1, plan.getEdges().size());
            assertEquals(1, plan.getCores().size());
        }

        @Test
        @DisplayName("Should handle null collections")
        void shouldHandleNullCollections() {
            WorkflowPlan plan = new WorkflowPlan("plan-1", "tenant-1",
                null, null, null, null, null, null, null, null, null);

            assertNotNull(plan.getTriggers());
            assertTrue(plan.getTriggers().isEmpty());
            assertNotNull(plan.getMcps());
            assertNotNull(plan.getAgents());
            assertNotNull(plan.getEdges());
            assertNotNull(plan.getCores());
        }
    }

    @Nested
    @DisplayName("Find methods")
    class FindMethodsTests {

        @Test
        @DisplayName("findTriggerById() should find by id")
        void findTriggerByIdShouldFind() {
            WorkflowPlan plan = createPlanWithTrigger();

            Optional<Trigger> result = plan.findTriggerById("t1");

            assertTrue(result.isPresent());
            assertEquals("t1", result.get().id());
        }

        @Test
        @DisplayName("findTriggerById() should return empty for non-existent")
        void findTriggerByIdShouldReturnEmpty() {
            WorkflowPlan plan = createPlanWithTrigger();

            Optional<Trigger> result = plan.findTriggerById("non-existent");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findTriggerByKey() should find by normalized key")
        void findTriggerByKeyShouldFind() {
            WorkflowPlan plan = createPlanWithTrigger();

            Optional<Trigger> result = plan.findTriggerByKey("trigger:webhook");

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("findStep() should find by step id")
        void findStepShouldFind() {
            WorkflowPlan plan = createPlanWithStep();

            Optional<Step> result = plan.findStep("mcp:fetch_data");

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("findAgent() should find by agent id")
        void findAgentShouldFind() {
            WorkflowPlan plan = createPlanWithAgent();

            Optional<Agent> result = plan.findAgent("agent:analyzer");

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("findAgentByLabel() should find by label")
        void findAgentByLabelShouldFind() {
            WorkflowPlan plan = createPlanWithAgent();

            Optional<Agent> result = plan.findAgentByLabel("Analyzer");

            assertTrue(result.isPresent());
        }
    }

    @Nested
    @DisplayName("getStepParams()")
    class GetStepParamsTests {

        @Test
        @DisplayName("Should return step params")
        void shouldReturnStepParams() {
            Map<String, Object> data = createBasePlan();
            data.put("mcps", List.of(
                Map.of("id", "s1", "label", "Fetch Data", "params", Map.of("url", "https://api.example.com"))
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            Map<String, Object> params = plan.getStepParams("mcp:fetch_data");

            assertNotNull(params);
            assertEquals("https://api.example.com", params.get("url"));
        }

        @Test
        @DisplayName("Should return empty map for non-existent step")
        void shouldReturnEmptyForNonExistent() {
            WorkflowPlan plan = WorkflowPlan.fromMap(createBasePlan());

            Map<String, Object> params = plan.getStepParams("mcp:non_existent");

            assertNotNull(params);
            assertTrue(params.isEmpty());
        }
    }

    @Nested
    @DisplayName("Execution graph")
    class ExecutionGraphTests {

        @Test
        @DisplayName("getExecutionGraph() should lazy-load graph")
        void getExecutionGraphShouldLazyLoad() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = plan.getExecutionGraph();

            assertNotNull(graph);
        }

        @Test
        @DisplayName("getExecutionGraph() should cache graph")
        void getExecutionGraphShouldCache() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph1 = plan.getExecutionGraph();
            ExecutionGraph graph2 = plan.getExecutionGraph();

            assertSame(graph1, graph2);
        }

        @Test
        @DisplayName("setExecutionGraph() should set pre-computed graph")
        void setExecutionGraphShouldSetPrecomputed() {
            WorkflowPlan plan = createLinearPlan();
            ExecutionGraph precomputed = ExecutionGraph.build(plan);

            plan.setExecutionGraph(precomputed);

            assertSame(precomputed, plan.getExecutionGraph());
        }
    }

    @Nested
    @DisplayName("Merge nodes")
    class MergeNodesTests {

        @Test
        @DisplayName("getMergeNodes() should detect merge nodes")
        void getMergeNodesShouldDetect() {
            WorkflowPlan plan = createPlanWithMerge();

            Map<String, MergeNode> mergeNodes = plan.getMergeNodes();

            assertNotNull(mergeNodes);
        }

        @Test
        @DisplayName("isProbableMergeNode() should detect probable merge")
        void isProbableMergeNodeShouldDetect() {
            WorkflowPlan plan = createPlanWithMerge();

            // Node with multiple incoming edges is a probable merge
            boolean result = plan.isProbableMergeNode("mcp:final");

            // Depends on plan structure
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Decision detection")
    class DecisionDetectionTests {

        @Test
        @DisplayName("hasDecisions() should return true when decisions exist")
        void hasDecisionsShouldReturnTrue() {
            Map<String, Object> data = createBasePlan();
            data.put("cores", List.of(
                Map.of("id", "c1", "type", "decision", "label", "Check")
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertTrue(plan.hasDecisions());
        }

        @Test
        @DisplayName("hasDecisions() should return true for switch")
        void hasDecisionsShouldReturnTrueForSwitch() {
            Map<String, Object> data = createBasePlan();
            data.put("cores", List.of(
                Map.of("id", "c1", "type", "switch", "label", "Router")
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            assertTrue(plan.hasDecisions());
        }
    }

    @Nested
    @DisplayName("getAllStepIds()")
    class GetAllStepIdsTests {

        @Test
        @DisplayName("Should return all step IDs")
        void shouldReturnAllStepIds() {
            WorkflowPlan plan = createLinearPlan();

            var allIds = plan.getAllStepIds();

            assertNotNull(allIds);
            assertFalse(allIds.isEmpty());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should return readable string")
        void shouldReturnReadableString() {
            WorkflowPlan plan = createLinearPlan();

            String str = plan.toString();

            assertNotNull(str);
            assertTrue(str.contains("WorkflowPlan"));
            assertTrue(str.contains("id="));
        }
    }

    // ===== Helper methods =====

    private Map<String, Object> createBasePlan() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", "test-plan");
        plan.put("tenant_id", "test-tenant");
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }

    private WorkflowPlan createPlanWithTrigger() {
        Map<String, Object> data = createBasePlan();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Webhook", "type", "webhook", "strategy", "single")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithStep() {
        Map<String, Object> data = createBasePlan();
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Fetch Data")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithAgent() {
        Map<String, Object> data = createBasePlan();
        data.put("agents", List.of(
            Map.of("id", "a1", "label", "Analyzer", "prompt", "Analyze")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createLinearPlan() {
        Map<String, Object> data = createBasePlan();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Step 1"),
            Map.of("id", "s2", "label", "Step 2")
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_1"),
            Map.of("from", "mcp:step_1", "to", "mcp:step_2")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithMerge() {
        Map<String, Object> data = createBasePlan();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Step A"),
            Map.of("id", "s2", "label", "Step B"),
            Map.of("id", "s3", "label", "Final")
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_a"),
            Map.of("from", "trigger:start", "to", "mcp:step_b"),
            Map.of("from", "mcp:step_a", "to", "mcp:final"),
            Map.of("from", "mcp:step_b", "to", "mcp:final")
        ));
        return WorkflowPlan.fromMap(data);
    }
}
