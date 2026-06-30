package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MergeNodeAnalyzer.
 * Tests merge node detection and dependency analysis.
 */
@ExtendWith(MockitoExtension.class)
class MergeNodeAnalyzerTest {

    private MergeNodeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new MergeNodeAnalyzer();
    }

    // ========================================================================
    // isMergeNode() Tests
    // ========================================================================

    @Nested
    @DisplayName("isMergeNode()")
    class IsMergeNodeTests {

        @Test
        @DisplayName("Should return true when step has multiple incoming edges from different sources")
        void shouldReturnTrueWhenMultipleSources() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "mcp:merge_point"),
                    new Edge("mcp:step2", "mcp:merge_point")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:merge_point");

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when step has single incoming edge")
        void shouldReturnFalseWhenSingleSource() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "mcp:step2")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:step2");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when step has no incoming edges")
        void shouldReturnFalseWhenNoIncomingEdges() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("trigger:start", "mcp:step1")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:nonexistent");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when plan is null")
        void shouldReturnFalseWhenPlanIsNull() {
            // When
            boolean result = analyzer.isMergeNode(null, "mcp:step1");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when edges list is null")
        void shouldReturnFalseWhenEdgesIsNull() {
            // Given
            WorkflowPlan plan = createPlanWithEdges(null);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:step1");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should handle edges with ports correctly (strip port from source)")
        void shouldHandleEdgesWithPorts() {
            // Given: Decision node with ports
            List<Edge> edges = List.of(
                    new Edge("core:check:if", "mcp:merge_point"),
                    new Edge("core:check:else", "mcp:merge_point")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:merge_point");

            // Then - Should be false because both edges come from same source (core:check)
            assertFalse(result);
        }

        @Test
        @DisplayName("Should detect merge when edges come from different decision nodes")
        void shouldDetectMergeFromDifferentDecisions() {
            // Given: Two different decision nodes merging
            List<Edge> edges = List.of(
                    new Edge("core:check1:if", "mcp:merge_point"),
                    new Edge("core:check2:else", "mcp:merge_point")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            boolean result = analyzer.isMergeNode(plan, "mcp:merge_point");

            // Then - Should be true because edges come from different sources
            assertTrue(result);
        }

        @Test
        @DisplayName("Should require full prefix for stepId")
        void shouldRequireFullPrefixForStepId() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "mcp:merge_point"),
                    new Edge("mcp:step2", "mcp:merge_point")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When - query with full prefix
            boolean result1 = analyzer.isMergeNode(plan, "mcp:merge_point");
            // When - query with just label (should return false - prefix required)
            boolean result2 = analyzer.isMergeNode(plan, "merge_point");

            // Then
            assertTrue(result1);
            assertFalse(result2); // Prefix is required for proper matching
        }
    }

    // ========================================================================
    // findPredecessorsFromEdges() Tests
    // ========================================================================

    @Nested
    @DisplayName("findPredecessorsFromEdges()")
    class FindPredecessorsFromEdgesTests {

        @Test
        @DisplayName("Should find all predecessors for a step")
        void shouldFindAllPredecessors() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("trigger:start", "mcp:step1"),
                    new Edge("mcp:step1", "mcp:step2"),
                    new Edge("mcp:step3", "mcp:step2")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            List<String> predecessors = analyzer.findPredecessorsFromEdges(plan, "mcp:step2");

            // Then
            assertEquals(2, predecessors.size());
            assertTrue(predecessors.contains("mcp:step1"));
            assertTrue(predecessors.contains("mcp:step3"));
        }

        @Test
        @DisplayName("Should return empty list when no predecessors")
        void shouldReturnEmptyListWhenNoPredecessors() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("trigger:start", "mcp:step1")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            List<String> predecessors = analyzer.findPredecessorsFromEdges(plan, "trigger:start");

            // Then
            assertTrue(predecessors.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list when plan is null")
        void shouldReturnEmptyListWhenPlanIsNull() {
            // When
            List<String> predecessors = analyzer.findPredecessorsFromEdges(null, "mcp:step1");

            // Then
            assertTrue(predecessors.isEmpty());
        }

        @Test
        @DisplayName("Should strip port from source edges")
        void shouldStripPortFromSourceEdges() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("core:check:if", "mcp:step1"),
                    new Edge("core:check:else", "mcp:step2")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);

            // When
            List<String> predecessors = analyzer.findPredecessorsFromEdges(plan, "mcp:step1");

            // Then
            assertEquals(1, predecessors.size());
            assertEquals("core:check", predecessors.get(0));
        }
    }

    // ========================================================================
    // isDependencyUnreachable() Tests
    // ========================================================================

    @Nested
    @DisplayName("isDependencyUnreachable()")
    class IsDependencyUnreachableTests {

        @Test
        @DisplayName("Should return true when all predecessors are skipped")
        void shouldReturnTrueWhenAllPredecessorsSkipped() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "mcp:target"),
                    new Edge("mcp:step2", "mcp:target")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            ExecutionGraph graph = createExecutionGraph(plan);

            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1", "mcp:step2"));
            Set<String> completedStepIds = new HashSet<>();

            // When
            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:target", skippedStepIds, completedStepIds);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when at least one predecessor is not skipped")
        void shouldReturnFalseWhenSomePredecessorsNotSkipped() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "mcp:target"),
                    new Edge("mcp:step2", "mcp:target")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            ExecutionGraph graph = createExecutionGraph(plan);

            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:step2"));

            // When
            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:target", skippedStepIds, completedStepIds);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when dependency has no predecessors (root node)")
        void shouldReturnFalseForRootNode() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("trigger:start", "mcp:step1")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            ExecutionGraph graph = createExecutionGraph(plan);

            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>();

            // When
            boolean result = analyzer.isDependencyUnreachable(plan, graph, "trigger:start", skippedStepIds, completedStepIds);

            // Then
            assertFalse(result);
        }
    }

    // ========================================================================
    // isDependencyParentCompleted() Tests
    // ========================================================================

    @Nested
    @DisplayName("isDependencyParentCompleted()")
    class IsDependencyParentCompletedTests {

        @Test
        @DisplayName("Should return true when parent of sub-node is completed")
        void shouldReturnTrueWhenParentCompleted() {
            // Given
            Set<String> completedStepIds = new HashSet<>(Set.of("core:loop"));

            // When - Sub-node ID with :: separator
            boolean result = analyzer.isDependencyParentCompleted("core:loop::condition_checker", completedStepIds);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when parent is not completed")
        void shouldReturnFalseWhenParentNotCompleted() {
            // Given
            Set<String> completedStepIds = new HashSet<>();

            // When
            boolean result = analyzer.isDependencyParentCompleted("core:loop::condition_checker", completedStepIds);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when no sub-node pattern (no ::)")
        void shouldReturnFalseWhenNoSubNodePattern() {
            // Given
            Set<String> completedStepIds = new HashSet<>(Set.of("core:loop"));

            // When - No :: separator
            boolean result = analyzer.isDependencyParentCompleted("core:loop", completedStepIds);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when dependencyId is null")
        void shouldReturnFalseWhenDependencyIdIsNull() {
            // Given
            Set<String> completedStepIds = new HashSet<>(Set.of("core:loop"));

            // When
            boolean result = analyzer.isDependencyParentCompleted(null, completedStepIds);

            // Then
            assertFalse(result);
        }
    }

    // ========================================================================
    // isDependencyParentSkipped() Tests
    // ========================================================================

    @Nested
    @DisplayName("isDependencyParentSkipped()")
    class IsDependencyParentSkippedTests {

        @Test
        @DisplayName("Should return true when parent of sub-node is skipped")
        void shouldReturnTrueWhenParentSkipped() {
            // Given
            Set<String> skippedStepIds = new HashSet<>(Set.of("core:loop"));

            // When
            boolean result = analyzer.isDependencyParentSkipped("core:loop::condition_checker", skippedStepIds);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when parent is not skipped")
        void shouldReturnFalseWhenParentNotSkipped() {
            // Given
            Set<String> skippedStepIds = new HashSet<>();

            // When
            boolean result = analyzer.isDependencyParentSkipped("core:loop::condition_checker", skippedStepIds);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when dependencyId is null")
        void shouldReturnFalseWhenDependencyIdIsNull() {
            // When
            boolean result = analyzer.isDependencyParentSkipped(null, new HashSet<>());

            // Then
            assertFalse(result);
        }
    }

    // ========================================================================
    // isLoopEntrySkipped() Tests
    // ========================================================================

    @Nested
    @DisplayName("isLoopEntrySkipped()")
    class IsLoopEntrySkippedTests {

        @Test
        @DisplayName("Should return true when loop entry predecessor is skipped")
        void shouldReturnTrueWhenLoopEntrySkipped() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "core:my_loop")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));

            // When
            boolean result = analyzer.isLoopEntrySkipped(plan, "core:my_loop", skippedStepIds);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when loop entry predecessor is not skipped")
        void shouldReturnFalseWhenLoopEntryNotSkipped() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "core:my_loop")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            Set<String> skippedStepIds = new HashSet<>();

            // When
            boolean result = analyzer.isLoopEntrySkipped(plan, "core:my_loop", skippedStepIds);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when plan is null")
        void shouldReturnFalseWhenPlanIsNull() {
            // When
            boolean result = analyzer.isLoopEntrySkipped(null, "core:my_loop", new HashSet<>());

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should handle normalized step IDs")
        void shouldHandleNormalizedStepIds() {
            // Given
            List<Edge> edges = List.of(
                    new Edge("mcp:my_step", "core:my_loop")
            );
            WorkflowPlan plan = createPlanWithEdges(edges);
            // Skipped with different normalization format
            Set<String> skippedStepIds = new HashSet<>(Set.of("my_step", "mcp:my_step"));

            // When
            boolean result = analyzer.isLoopEntrySkipped(plan, "core:my_loop", skippedStepIds);

            // Then
            assertTrue(result);
        }
    }

    // ========================================================================
    // addSkippedCores() Tests
    // ========================================================================

    @Nested
    @DisplayName("addSkippedCores()")
    class AddSkippedCoresTests {

        @Test
        @DisplayName("Should mark core as skipped when predecessor is skipped")
        void shouldMarkCoreAsSkippedWhenPredecessorSkipped() {
            // Given
            Core decisionCore = createCore("decision", "Check", "core-1");
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "core:check")
            );
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(decisionCore), edges);

            Set<String> completedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));

            // When
            analyzer.addSkippedCores(plan, completedStepIds, skippedStepIds);

            // Then
            assertTrue(skippedStepIds.contains("core:check"));
        }

        @Test
        @DisplayName("Should not mark core as skipped when predecessor is completed")
        void shouldNotMarkCoreAsSkippedWhenPredecessorCompleted() {
            // Given
            Core decisionCore = createCore("decision", "Check", "core-1");
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "core:check")
            );
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(decisionCore), edges);

            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:step1"));
            Set<String> skippedStepIds = new HashSet<>();

            // When
            analyzer.addSkippedCores(plan, completedStepIds, skippedStepIds);

            // Then
            assertFalse(skippedStepIds.contains("core:check"));
        }

        @Test
        @DisplayName("Should not mark already completed core as skipped")
        void shouldNotMarkAlreadyCompletedCoreAsSkipped() {
            // Given
            Core decisionCore = createCore("decision", "Check", "core-1");
            List<Edge> edges = List.of(
                    new Edge("mcp:step1", "core:check")
            );
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(decisionCore), edges);

            Set<String> completedStepIds = new HashSet<>(Set.of("core:check"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));

            int initialSize = skippedStepIds.size();

            // When
            analyzer.addSkippedCores(plan, completedStepIds, skippedStepIds);

            // Then - Should not add core:check to skipped (already completed)
            assertEquals(initialSize, skippedStepIds.size());
        }

        @Test
        @DisplayName("Should handle null plan gracefully")
        void shouldHandleNullPlanGracefully() {
            // Given
            Set<String> completedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();

            // When / Then - Should not throw
            assertDoesNotThrow(() -> analyzer.addSkippedCores(null, completedStepIds, skippedStepIds));
        }
    }

    // ========================================================================
    // addCompletedLoopConditionCheckers() Tests
    // ========================================================================

    @Nested
    @DisplayName("addCompletedLoopConditionCheckers()")
    class AddCompletedLoopConditionCheckersTests {

        @Test
        @DisplayName("Should add condition_checker when loop has exit reason")
        void shouldAddConditionCheckerWhenLoopHasExitReason() {
            // Given
            Core loopCore = createCore("loop", "While Loop", "loop-1");
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(loopCore), List.of());

            WorkflowStepDataEntity loopEntity = createStepEntity("while_loop", "COMPLETED");
            loopEntity.setLoopExitReason("max_iterations");
            List<WorkflowStepDataEntity> stepEntities = List.of(loopEntity);

            Set<String> completedStepIds = new HashSet<>(Set.of("core:while_loop"));

            // When
            analyzer.addCompletedLoopConditionCheckers(plan, stepEntities, completedStepIds);

            // Then
            assertTrue(completedStepIds.contains("core:while_loop::condition_checker"));
        }

        @Test
        @DisplayName("Should add condition_checker when loop condition is false")
        void shouldAddConditionCheckerWhenConditionFalse() {
            // Given
            Core loopCore = createCore("loop", "While Loop", "loop-1");
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(loopCore), List.of());

            WorkflowStepDataEntity loopEntity = createStepEntity("while_loop", "COMPLETED");
            loopEntity.setConditionResult(false);
            List<WorkflowStepDataEntity> stepEntities = List.of(loopEntity);

            Set<String> completedStepIds = new HashSet<>(Set.of("core:while_loop"));

            // When
            analyzer.addCompletedLoopConditionCheckers(plan, stepEntities, completedStepIds);

            // Then
            assertTrue(completedStepIds.contains("core:while_loop::condition_checker"));
        }

        @Test
        @DisplayName("Should not add condition_checker when loop is not completed")
        void shouldNotAddConditionCheckerWhenLoopNotCompleted() {
            // Given
            Core loopCore = createCore("loop", "While Loop", "loop-1");
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(loopCore), List.of());

            WorkflowStepDataEntity loopEntity = createStepEntity("while_loop", "RUNNING");
            loopEntity.setLoopExitReason("max_iterations");
            List<WorkflowStepDataEntity> stepEntities = List.of(loopEntity);

            Set<String> completedStepIds = new HashSet<>(); // Loop NOT completed

            // When
            analyzer.addCompletedLoopConditionCheckers(plan, stepEntities, completedStepIds);

            // Then
            assertFalse(completedStepIds.contains("core:while_loop::condition_checker"));
        }

        @Test
        @DisplayName("Should not add condition_checker when loop has no exit reason and condition is not false")
        void shouldNotAddConditionCheckerWhenNoExitAndConditionNotFalse() {
            // Given
            Core loopCore = createCore("loop", "While Loop", "loop-1");
            WorkflowPlan plan = createPlanWithCoresAndEdges(List.of(loopCore), List.of());

            WorkflowStepDataEntity loopEntity = createStepEntity("while_loop", "COMPLETED");
            // No exit reason, no condition_result set (or true)
            List<WorkflowStepDataEntity> stepEntities = List.of(loopEntity);

            Set<String> completedStepIds = new HashSet<>(Set.of("core:while_loop"));

            // When
            analyzer.addCompletedLoopConditionCheckers(plan, stepEntities, completedStepIds);

            // Then
            assertFalse(completedStepIds.contains("core:while_loop::condition_checker"));
        }

        @Test
        @DisplayName("Should handle null plan gracefully")
        void shouldHandleNullPlanGracefully() {
            // Given
            Set<String> completedStepIds = new HashSet<>();

            // When / Then
            assertDoesNotThrow(() -> analyzer.addCompletedLoopConditionCheckers(null, List.of(), completedStepIds));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private WorkflowPlan createPlanWithEdges(List<Edge> edges) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                List.of(),  // triggers
                List.of(),  // mcps
                null,       // agents
                edges != null ? edges : List.of(),
                null, null, null, null, Map.of()
        );
    }

    private WorkflowPlan createPlanWithCoresAndEdges(List<Core> cores, List<Edge> edges) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                List.of(),  // triggers
                List.of(),  // mcps
                null,       // agents
                edges != null ? edges : List.of(),
                cores != null ? cores : List.of(),
                null, null, null, Map.of()
        );
    }

    private Core createCore(String type, String label, String id) {
        return new Core(
                id,        // id
                type,      // type
                null,      // position
                label,     // label
                null,      // decisionConditions
                null,      // switchExpression
                null,      // switchCases
                null,      // loopCondition
                null,      // maxIterations
                null,      // strategy
                null,      // list
                null,      // maxItems
                null,      // splitStrategy
                null,      // forkOutputs
                null,      // transformConfig
                null,      // waitConfig
                null,      // downloadConfig
                null,      // responseConfig
                null,      // aggregateConfig
                null,      // optionChoices
                null,      // httpRequestConfig
                null,      // approvalConfig
                null,      // dataInputConfig
                null,      // filterConfig
                null,      // sortConfig
                null,      // limitConfig
                null,      // removeDuplicatesConfig
                null,      // summarizeConfig
                null,      // dateTimeConfig
                null,      // cryptoJwtConfig
                null,      // xmlConfig
                null,      // compressionConfig
                null,      // rssConfig
                null,      // convertToFileConfig
                null,      // extractFromFileConfig
                null,      // compareDatasetsConfig
                null,      // subWorkflowConfig
                null,      // respondToWebhookConfig
                null,      // sendEmailConfig
                null,      // emailInboxConfig
                null,      // codeConfig
                null,      // setConfig
                null,      // htmlExtractConfig
                null,      // taskConfig
                null,      // stopOnErrorConfig
                null,      // sshConfig
                null,      // sftpConfig
                null,      // databaseConfig
                null,      // params
                null       // graphNodeId
        );
    }

    private ExecutionGraph createExecutionGraph(WorkflowPlan plan) {
        return ExecutionGraph.build(plan);
    }

    private WorkflowStepDataEntity createStepEntity(String alias, String status) {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        entity.setStepAlias(alias);
        entity.setStatus(status);
        return entity;
    }
}
