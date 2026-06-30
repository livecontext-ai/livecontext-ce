package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionGraph class.
 *
 * ExecutionGraph builds and represents the dependency graph from workflow edges.
 */
@DisplayName("ExecutionGraph")
class ExecutionGraphTest {

    @Nested
    @DisplayName("build()")
    class BuildTests {

        @Test
        @DisplayName("Should build graph from linear plan")
        void shouldBuildGraphFromLinearPlan() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            assertNotNull(graph);
            assertTrue(graph.isValid());
            assertFalse(graph.hasCycles());
        }

        @Test
        @DisplayName("Should build graph with correct dependencies")
        void shouldBuildGraphWithCorrectDependencies() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // step_1 depends on start
            Set<String> step1Deps = graph.getDependencies("mcp:step_1");
            assertTrue(step1Deps.contains("trigger:start"));

            // step_2 depends on step_1
            Set<String> step2Deps = graph.getDependencies("mcp:step_2");
            assertTrue(step2Deps.contains("mcp:step_1"));
        }

        @Test
        @DisplayName("Should build graph with correct dependents")
        void shouldBuildGraphWithCorrectDependents() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // start has step_1 as dependent
            Set<String> startDependents = graph.getDependents("trigger:start");
            assertTrue(startDependents.contains("mcp:step_1"));
        }

        @Test
        @DisplayName("Should handle parallel branches (fork)")
        void shouldHandleParallelBranches() {
            WorkflowPlan plan = createForkPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            assertTrue(graph.isValid());

            // Both branches depend on trigger
            Set<String> stepADeps = graph.getDependencies("mcp:step_a");
            Set<String> stepBDeps = graph.getDependencies("mcp:step_b");

            assertTrue(stepADeps.contains("trigger:start"));
            assertTrue(stepBDeps.contains("trigger:start"));
        }

        @Test
        @DisplayName("Should handle merge node")
        void shouldHandleMergeNode() {
            WorkflowPlan plan = createMergePlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // Final depends on both step_a and step_b
            Set<String> finalDeps = graph.getDependencies("mcp:final");
            assertTrue(finalDeps.contains("mcp:step_a"));
            assertTrue(finalDeps.contains("mcp:step_b"));
        }

        @Test
        @DisplayName("Should include core nodes in graph")
        void shouldIncludeCoreNodesInGraph() {
            WorkflowPlan plan = createDecisionPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // Decision node should be in the graph
            Set<String> allDeps = graph.getAllDependencies().keySet();
            assertTrue(allDeps.stream().anyMatch(k -> k.startsWith("core:")));
        }
    }

    @Nested
    @DisplayName("Execution levels")
    class ExecutionLevelsTests {

        @Test
        @DisplayName("Should calculate execution levels")
        void shouldCalculateExecutionLevels() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // Trigger at level 0
            assertEquals(0, graph.getLevel("trigger:start"));

            // First step at level 1
            assertEquals(1, graph.getLevel("mcp:step_1"));

            // Second step at level 2
            assertEquals(2, graph.getLevel("mcp:step_2"));
        }

        @Test
        @DisplayName("getStepsAtLevel() should return nodes at level")
        void getStepsAtLevelShouldReturnNodes() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            List<String> level0 = graph.getStepsAtLevel(0);
            assertTrue(level0.contains("trigger:start"));
        }

        @Test
        @DisplayName("getMaxLevel() should return highest level")
        void getMaxLevelShouldReturnHighest() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            assertTrue(graph.getMaxLevel() >= 2);
        }

        @Test
        @DisplayName("getAllLevels() should return all levels")
        void getAllLevelsShouldReturnAllLevels() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            Map<Integer, List<String>> levels = graph.getAllLevels();
            assertFalse(levels.isEmpty());
            assertTrue(levels.containsKey(0));
        }
    }

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when all dependencies completed")
        void shouldReturnTrueWhenAllDepsCompleted() {
            WorkflowPlan plan = createLinearPlan();
            ExecutionGraph graph = ExecutionGraph.build(plan);

            Set<String> completed = Set.of("trigger:start");

            assertTrue(graph.canExecute("mcp:step_1", completed));
        }

        @Test
        @DisplayName("Should return false when dependencies incomplete")
        void shouldReturnFalseWhenDepsIncomplete() {
            WorkflowPlan plan = createLinearPlan();
            ExecutionGraph graph = ExecutionGraph.build(plan);

            Set<String> completed = Set.of(); // No completed nodes

            assertFalse(graph.canExecute("mcp:step_1", completed));
        }

        @Test
        @DisplayName("Should return true for nodes with no dependencies")
        void shouldReturnTrueForNoDeps() {
            WorkflowPlan plan = createLinearPlan();
            ExecutionGraph graph = ExecutionGraph.build(plan);

            Set<String> completed = Set.of();

            assertTrue(graph.canExecute("trigger:start", completed));
        }
    }

    @Nested
    @DisplayName("Cycle detection")
    class CycleDetectionTests {

        @Test
        @DisplayName("Should detect no cycles in linear graph")
        void shouldDetectNoCyclesInLinear() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            assertFalse(graph.hasCycles());
        }

        @Test
        @DisplayName("isValid() should be true for acyclic graph")
        void isValidShouldBeTrueForAcyclic() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            assertTrue(graph.isValid());
        }
    }

    @Nested
    @DisplayName("getParallelSteps()")
    class GetParallelStepsTests {

        @Test
        @DisplayName("Should return steps at same level")
        void shouldReturnStepsAtSameLevel() {
            WorkflowPlan plan = createForkPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            // step_a and step_b should be at same level
            List<String> parallelA = graph.getParallelSteps("mcp:step_a");

            // They are parallel if at same level
            if (!parallelA.isEmpty()) {
                int levelA = graph.getLevel("mcp:step_a");
                for (String parallel : parallelA) {
                    assertEquals(levelA, graph.getLevel(parallel));
                }
            }
        }
    }

    @Nested
    @DisplayName("getCriticalPath()")
    class GetCriticalPathTests {

        @Test
        @DisplayName("Should return path from start to end")
        void shouldReturnPathFromStartToEnd() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            List<String> criticalPath = graph.getCriticalPath();

            assertNotNull(criticalPath);
            assertFalse(criticalPath.isEmpty());
        }
    }

    @Nested
    @DisplayName("getStatistics()")
    class GetStatisticsTests {

        @Test
        @DisplayName("Should return graph statistics")
        void shouldReturnGraphStatistics() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            ExecutionGraph.GraphStatistics stats = graph.getStatistics();

            assertNotNull(stats);
            assertTrue(stats.totalSteps() > 0);
            assertTrue(stats.totalEdges() > 0);
            assertEquals(graph.isValid(), stats.isValid());
            assertEquals(graph.hasCycles(), stats.hasCycles());
        }
    }

    @Nested
    @DisplayName("toTextRepresentation()")
    class ToTextRepresentationTests {

        @Test
        @DisplayName("Should return readable text")
        void shouldReturnReadableText() {
            WorkflowPlan plan = createLinearPlan();

            ExecutionGraph graph = ExecutionGraph.build(plan);

            String text = graph.toTextRepresentation();

            assertNotNull(text);
            assertTrue(text.contains("ExecutionGraph"));
            assertTrue(text.contains("Valid:"));
            assertTrue(text.contains("Level"));
        }
    }

    @Nested
    @DisplayName("Default constructor and setters")
    class DefaultConstructorTests {

        @Test
        @DisplayName("Default constructor should create empty graph")
        void defaultConstructorShouldCreateEmptyGraph() {
            ExecutionGraph graph = new ExecutionGraph();

            assertNotNull(graph);
            assertFalse(graph.isValid());
            assertEquals(0, graph.getMaxLevel());
        }

        @Test
        @DisplayName("Setters should update graph state")
        void settersShouldUpdateGraphState() {
            ExecutionGraph graph = new ExecutionGraph();

            graph.setValid(true);
            graph.setMaxLevel(5);
            graph.setHasCycles(false);

            assertTrue(graph.isValid());
            assertEquals(5, graph.getMaxLevel());
            assertFalse(graph.hasCycles());
        }
    }

    // ===== Helper methods =====

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

    private WorkflowPlan createForkPlan() {
        Map<String, Object> data = createBasePlan();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Step A"),
            Map.of("id", "s2", "label", "Step B")
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_a"),
            Map.of("from", "trigger:start", "to", "mcp:step_b")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createMergePlan() {
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

    private WorkflowPlan createDecisionPlan() {
        Map<String, Object> data = createBasePlan();
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Success"),
            Map.of("id", "s2", "label", "Failure")
        ));
        // Use HashMap for else condition since Map.of() doesn't allow null values
        Map<String, Object> elseCondition = new HashMap<>();
        elseCondition.put("id", "cond2");
        elseCondition.put("type", "else");
        elseCondition.put("label", "Fail");
        elseCondition.put("expression", null);
        data.put("cores", List.of(
            Map.of("id", "c1", "type", "decision", "label", "Check",
                "decisionConditions", List.of(
                    Map.of("id", "cond1", "type", "if", "label", "OK", "expression", "true"),
                    elseCondition
                ))
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:check"),
            Map.of("from", "core:check:if", "to", "mcp:success"),
            Map.of("from", "core:check:else", "to", "mcp:failure")
        ));
        return WorkflowPlan.fromMap(data);
    }

    private Map<String, Object> createBasePlan() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", "test-plan");
        plan.put("tenant_id", "test-tenant");
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }
}
