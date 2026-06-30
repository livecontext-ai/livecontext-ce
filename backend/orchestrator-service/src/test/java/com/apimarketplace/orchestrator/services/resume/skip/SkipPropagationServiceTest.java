package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkipPropagationService")
class SkipPropagationServiceTest {

    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private EdgeStatusService edgeStatusService;
    @Mock private SkipGraphAnalyzer graphAnalyzer;
    @Mock private ReachabilityAnalyzer reachabilityAnalyzer;
    @Mock private VirtualNodeSkipHandler virtualNodeSkipHandler;
    @Mock private SkippedStepCleanup skippedStepCleanup;
    @Mock private ParentChildChecker parentChildChecker;

    private SkipPropagationService service;

    @BeforeEach
    void setUp() {
        service = new SkipPropagationService(
            stepCompletionOrchestrator, edgeStatusService, graphAnalyzer,
            reachabilityAnalyzer, virtualNodeSkipHandler,
            skippedStepCleanup, parentChildChecker);
    }

    @Nested
    @DisplayName("propagateSkipToSuccessors()")
    class PropagateSkipTests {

        @Test
        @DisplayName("Should not revisit already visited nodes")
        void shouldNotRevisitNodes() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();
            visited.add("mcp:step1");

            service.propagateSkipToSuccessors(execution, plan, "mcp:step1", "core:decision", visited);

            verifyNoInteractions(graphAnalyzer);
        }

        @Test
        @DisplayName("Should stop when no successors found")
        void shouldStopWhenNoSuccessors() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();

            when(graphAnalyzer.findSuccessorsFromEdges(plan, "mcp:step1")).thenReturn(List.of());

            service.propagateSkipToSuccessors(execution, plan, "mcp:step1", "core:decision", visited);

            verify(graphAnalyzer).findSuccessorsFromEdges(plan, "mcp:step1");
            verifyNoInteractions(stepCompletionOrchestrator);
        }

        @Test
        @DisplayName("Should stop at merge nodes and mark edge skipped")
        void shouldStopAtMergeNodes() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();

            when(graphAnalyzer.findSuccessorsFromEdges(plan, "mcp:step1")).thenReturn(List.of("core:merge1"));
            when(graphAnalyzer.isMergeNode(plan, "core:merge1")).thenReturn(true);

            service.propagateSkipToSuccessors(execution, plan, "mcp:step1", "core:decision", visited);

            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "core:merge1");
            verifyNoInteractions(stepCompletionOrchestrator);
        }

        @Test
        @DisplayName("Should stop at direct decision destinations and mark edge skipped")
        void shouldStopAtDirectDecisionDestinations() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();

            when(graphAnalyzer.findSuccessorsFromEdges(plan, "mcp:step1")).thenReturn(List.of("mcp:step2"));
            when(graphAnalyzer.isMergeNode(plan, "mcp:step2")).thenReturn(false);
            when(graphAnalyzer.isDirectDecisionDestination(plan, "mcp:step2", "core:decision")).thenReturn(true);

            service.propagateSkipToSuccessors(execution, plan, "mcp:step1", "core:decision", visited);

            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "mcp:step2");
            verifyNoInteractions(stepCompletionOrchestrator);
        }

        @Test
        @DisplayName("Should skip normal successors and propagate recursively")
        void shouldSkipNormalSuccessors() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();

            when(graphAnalyzer.findSuccessorsFromEdges(plan, "mcp:step1")).thenReturn(List.of("mcp:step2"));
            when(graphAnalyzer.isMergeNode(plan, "mcp:step2")).thenReturn(false);
            when(graphAnalyzer.isDirectDecisionDestination(plan, "mcp:step2", "core:decision")).thenReturn(false);
            // Step2 has no further successors
            when(graphAnalyzer.findSuccessorsFromEdges(plan, "mcp:step2")).thenReturn(List.of());

            service.propagateSkipToSuccessors(execution, plan, "mcp:step1", "core:decision", visited);

            verify(execution).setStepResult(eq("mcp:step2"), any(StepExecutionResult.class));
            verify(stepCompletionOrchestrator).completeSkippedStep(eq(execution), eq("mcp:step2"), anyString(), anyString(), eq("core:decision"), eq(0));
            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "mcp:step2");
        }

        @Test
        @DisplayName("Should use SkipPropagationContext overload")
        void shouldUseContextOverload() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> visited = new HashSet<>();
            visited.add("mcp:step1"); // Already visited to prevent recursion

            SkipPropagationContext context = new SkipPropagationContext(
                execution, plan, "mcp:step1", "core:decision", visited);

            service.propagateSkipToSuccessors(context);

            // Already visited, so no work done
            verifyNoInteractions(graphAnalyzer);
        }
    }

    @Nested
    @DisplayName("Delegated methods")
    class DelegatedMethodsTests {

        @Test
        @DisplayName("addSkippedCores should delegate to VirtualNodeSkipHandler")
        void shouldDelegateAddSkippedCores() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> completed = new HashSet<>();
            Set<String> skipped = new HashSet<>();

            service.addSkippedCores(plan, completed, skipped);

            verify(virtualNodeSkipHandler).addSkippedCores(plan, completed, skipped);
        }

        @Test
        @DisplayName("removeSkippedStepsWithCompletedPredecessors should delegate to SkippedStepCleanup")
        void shouldDelegateRemoveSkipped() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Set<String> completed = new HashSet<>();
            Set<String> skipped = new HashSet<>();

            service.removeSkippedStepsWithCompletedPredecessors(plan, completed, skipped);

            verify(skippedStepCleanup).removeSkippedStepsWithCompletedPredecessors(plan, completed, skipped);
        }

        @Test
        @DisplayName("isDependencyUnreachable should delegate to ReachabilityAnalyzer")
        void shouldDelegateIsDependencyUnreachable() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            ExecutionGraph graph = mock(ExecutionGraph.class);
            Set<String> skipped = Set.of("mcp:a");
            Set<String> completed = Set.of("mcp:b");

            when(reachabilityAnalyzer.isDependencyUnreachable(plan, graph, "mcp:x", skipped, completed)).thenReturn(true);

            assertTrue(service.isDependencyUnreachable(plan, graph, "mcp:x", skipped, completed));
        }

        @Test
        @DisplayName("isDependencyUnreachableShallow should delegate to ReachabilityAnalyzer")
        void shouldDelegateIsDependencyUnreachableShallow() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            ExecutionGraph graph = mock(ExecutionGraph.class);

            when(reachabilityAnalyzer.isDependencyUnreachableShallow(plan, graph, "mcp:x", Set.of(), Set.of())).thenReturn(false);

            assertFalse(service.isDependencyUnreachableShallow(plan, graph, "mcp:x", Set.of(), Set.of()));
        }

        @Test
        @DisplayName("areAllPredecessorsUnreachable should delegate to ReachabilityAnalyzer")
        void shouldDelegateAreAllPredecessorsUnreachable() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            ExecutionGraph graph = mock(ExecutionGraph.class);
            List<String> preds = List.of("mcp:a");
            Set<String> visited = new HashSet<>();

            when(reachabilityAnalyzer.areAllPredecessorsUnreachable(plan, graph, "mcp:x", preds, Set.of(), Set.of(), visited)).thenReturn(true);

            assertTrue(service.areAllPredecessorsUnreachable(plan, graph, "mcp:x", preds, Set.of(), Set.of(), visited));
        }

        @Test
        @DisplayName("isDependencyParentCompleted should delegate to ParentChildChecker")
        void shouldDelegateDependencyParentCompleted() {
            when(parentChildChecker.isDependencyParentCompleted("mcp:x", Set.of("mcp:parent"))).thenReturn(true);

            assertTrue(service.isDependencyParentCompleted("mcp:x", Set.of("mcp:parent")));
        }

        @Test
        @DisplayName("isDependencyParentSkipped should delegate to ParentChildChecker")
        void shouldDelegateDependencyParentSkipped() {
            when(parentChildChecker.isDependencyParentSkipped("mcp:x", Set.of("mcp:parent"))).thenReturn(false);

            assertFalse(service.isDependencyParentSkipped("mcp:x", Set.of("mcp:parent")));
        }

        @Test
        @DisplayName("normalizeNodeId should delegate to SkipGraphAnalyzer")
        void shouldDelegateNormalizeNodeId() {
            when(graphAnalyzer.normalizeNodeId("myStep")).thenReturn("mcp:my_step");

            assertEquals("mcp:my_step", service.normalizeNodeId("myStep"));
        }

        @Test
        @DisplayName("extractLabel should delegate to SkipGraphAnalyzer")
        void shouldDelegateExtractLabel() {
            when(graphAnalyzer.extractLabel("mcp:my_step")).thenReturn("my_step");

            assertEquals("my_step", service.extractLabel("mcp:my_step"));
        }
    }
}
