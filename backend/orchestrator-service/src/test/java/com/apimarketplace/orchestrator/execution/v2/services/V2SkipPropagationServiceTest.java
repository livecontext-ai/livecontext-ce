package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2SkipPropagationService.
 *
 * This service handles propagating skip status through workflow nodes,
 * including persisting skipped nodes to database and emitting SKIPPED edge events.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V2SkipPropagationService")
class V2SkipPropagationServiceTest {

    @Mock
    private StepCompletionOrchestrator stepCompletionOrchestrator;

    @Mock
    private EdgeStatusService edgeStatusService;

    @Mock
    private WorkflowExecution execution;

    private V2SkipPropagationService skipPropagationService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        skipPropagationService = new V2SkipPropagationService(
            stepCompletionOrchestrator,
            edgeStatusService,
            meterRegistry
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // persistAndPropagateSkip() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("persistAndPropagateSkip()")
    class PersistAndPropagateSkipTests {

        @Test
        @DisplayName("Should persist skipped node to database")
        void shouldPersistSkippedNodeToDatabase() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then
            verify(stepCompletionOrchestrator).completeSkipped(any(), any());
        }

        @Test
        @DisplayName("Cell 9B: a merge reached DIRECTLY by an unselected branch port is NOT over-skipped via the direct path")
        void directPortToMergeDoesNotOverSkipViaDirectPath() {
            // Ops Health Judge v2 (switch): case_0 (OK) -> log_verdict DIRECTLY. On a WARN/CRIT
            // cycle case_0 is unselected, so persistAndPropagateSkip is called with the MERGE
            // itself as the skippedNode. Pre-fix this committed the merge SKIPPED unconditionally
            // (completeSkipped) - over-skipping it while the selected branch (mcp:alert_warn) was
            // still completing, so log_verdict + noc_console never ran. The isMergeNode guard now
            // defers to the predecessor-aware handler, which does NOT skip a merge whose
            // predecessors are not all resolved.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("table:log_verdict");
            when(merge.isMergeNode()).thenReturn(true);
            when(merge.getPredecessorIds()).thenReturn(List.of(
                "core:route_severity:case_0", "mcp:alert_warn", "mcp:alert_crit"));

            skipPropagationService.persistAndPropagateSkip(
                execution, merge, "core:route_severity", 0, 0, "trigger:cron");

            // The merge must NOT be committed SKIPPED via the un-guarded direct path.
            verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
        }

        @Test
        @DisplayName("Merge contract: 1 COMPLETED + 2 SKIPPED predecessors → merge RUNS, not skipped (explicit core:merge)")
        void mergeOneCompletedTwoSkippedRunsExplicit() {
            // The exact Ops Health Judge steady state: exactly one severity lane completes, the
            // other two are skipped. anyCompleted=true → the merge MUST run (log the verdict),
            // never be committed SKIPPED.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("table:log_verdict");
            when(merge.isMergeNode()).thenReturn(true);
            when(merge.getPredecessorIds()).thenReturn(List.of("mcp:alert_warn", "mcp:alert_crit", "core:route_severity:case_0"));
            when(execution.getStepResult("mcp:alert_warn")).thenReturn(StepExecutionResult.success("mcp:alert_warn", Map.of(), 0));
            when(execution.getStepResult("mcp:alert_crit")).thenReturn(StepExecutionResult.skipped("mcp:alert_crit", "skipped"));
            // Port-qualified predecessor "core:route_severity:case_0" resolves to the bare node id the
            // step result is keyed by ("core:route_severity") - see predecessorNodeId().
            when(execution.getStepResult("core:route_severity")).thenReturn(StepExecutionResult.skipped("core:route_severity", "skipped"));

            // The skip of the last skipped lane reaches the merge.
            skipPropagationService.persistAndPropagateSkip(execution, merge, "mcp:alert_crit", 0, 0, "trigger:cron");

            verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("table:log_verdict"), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Merge contract: 1 COMPLETED + 2 SKIPPED predecessors → merge RUNS, not skipped (IMPLICIT merge)")
        void mergeOneCompletedTwoSkippedRunsImplicit() {
            // Same contract for an implicit merge (any node with >=2 incoming edges).
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("mcp:join");
            when(merge.isImplicitMerge()).thenReturn(true);
            when(merge.getPredecessorIds()).thenReturn(List.of("mcp:a", "mcp:b", "mcp:c"));
            when(execution.getStepResult("mcp:a")).thenReturn(StepExecutionResult.success("mcp:a", Map.of(), 0));
            when(execution.getStepResult("mcp:b")).thenReturn(StepExecutionResult.skipped("mcp:b", "skipped"));
            when(execution.getStepResult("mcp:c")).thenReturn(StepExecutionResult.skipped("mcp:c", "skipped"));

            skipPropagationService.persistAndPropagateSkip(execution, merge, "mcp:c", 0, 0, "trigger:cron");

            verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("mcp:join"), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Fix: a merge fed DIRECTLY by a skipped branching node's port edges converges and is SKIPPED")
        void shouldSkipMergeFedBySkippedBranchingNodePorts() {
            // join's predecessors are PORT-QUALIFIED (a decision/guardrail wired directly into the
            // merge via both ports). The branching node core:gate was skipped by the failure cascade
            // and its result is keyed by the BARE node id. Pre-fix the convergence check looked up
            // "core:gate:if"/"core:gate:else" (always null) and left the merge unreached forever.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(merge.getPredecessorIds()).thenReturn(List.of("core:gate:if", "core:gate:else"));
            when(merge.getSuccessors()).thenReturn(List.of());
            when(merge.getAllChildNodes()).thenReturn(List.of());
            when(execution.getStepResult("core:gate")).thenReturn(
                StepExecutionResult.skipped("core:gate", "skipped"));

            skipPropagationService.markEdgeToMergeSkipped(execution, merge, "core:gate", 0, 0, "trigger:cron");

            // All predecessors resolved (the bare node is SKIPPED) and none COMPLETED → skip the merge.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:join"), any(), any(), any(), eq(0), eq(0), eq("trigger:cron"));
        }

        @Test
        @DisplayName("A merge fed by a branching node whose bare node COMPLETED is NOT skipped (runs)")
        void shouldNotSkipMergeWhenBranchingNodeCompleted() {
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(merge.getPredecessorIds()).thenReturn(List.of("core:gate:if", "core:gate:else"));
            when(execution.getStepResult("core:gate")).thenReturn(
                StepExecutionResult.success("core:gate", Map.of(), 0));

            skipPropagationService.markEdgeToMergeSkipped(execution, merge, "core:gate", 0, 0, "trigger:cron");

            // The decision COMPLETED (one branch taken) → the merge must run, not be skipped.
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:join"), any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Fix: a merge fed by a skipped GUARDRAIL agent's pass/fail ports converges and is SKIPPED (agent port resolution)")
        void shouldSkipMergeFedBySkippedGuardrailPorts() {
            // Same gap as decision ports, but for an agent (guardrail) - exercises the agent branch
            // of predecessorNodeId ("agent:risk_screen:pass" -> "agent:risk_screen").
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(merge.getPredecessorIds()).thenReturn(List.of("agent:risk_screen:pass", "agent:risk_screen:fail"));
            when(merge.getSuccessors()).thenReturn(List.of());
            when(merge.getAllChildNodes()).thenReturn(List.of());
            when(execution.getStepResult("agent:risk_screen")).thenReturn(
                StepExecutionResult.skipped("agent:risk_screen", "skipped"));

            skipPropagationService.markEdgeToMergeSkipped(execution, merge, "agent:risk_screen", 0, 0, "trigger:cron");

            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:join"), any(), any(), any(), eq(0), eq(0), eq("trigger:cron"));
        }

        @Test
        @DisplayName("Fix: a merge mixing a regular and a port-qualified predecessor, both SKIPPED, converges and is SKIPPED")
        void shouldSkipMergeWithMixedRegularAndPortPredecessorsAllSkipped() {
            // One plain predecessor + one branching-port predecessor, both skipped. Pre-fix the
            // port-qualified one ("core:gate:else") was looked up verbatim -> null -> never converged.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(merge.getPredecessorIds()).thenReturn(List.of("mcp:plain_branch", "core:gate:else"));
            when(merge.getSuccessors()).thenReturn(List.of());
            when(merge.getAllChildNodes()).thenReturn(List.of());
            when(execution.getStepResult("mcp:plain_branch")).thenReturn(
                StepExecutionResult.skipped("mcp:plain_branch", "skipped"));
            when(execution.getStepResult("core:gate")).thenReturn(
                StepExecutionResult.skipped("core:gate", "skipped"));

            skipPropagationService.markEdgeToMergeSkipped(execution, merge, "core:gate", 0, 0, "trigger:cron");

            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:join"), any(), any(), any(), eq(0), eq(0), eq("trigger:cron"));
        }

        @Test
        @DisplayName("Should emit SKIPPED edges to successors")
        void shouldEmitSkippedEdgesToSuccessors() {
            // Given
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");
            when(successor.getSuccessors()).thenReturn(List.of());

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(successor));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("mcp:step1"), eq("mcp:step2"), eq(0));
        }

        @Test
        @DisplayName("Should handle node with no successors")
        void shouldHandleNodeWithNoSuccessors() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:end_step");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then
            verify(stepCompletionOrchestrator).completeSkipped(any(), any());
            verify(edgeStatusService, never()).markEdgeSkipped(any(), any(), any(), anyInt());
        }
    }

    // NOTE: emitSkippedOutgoingEdges and emitSkippedOutgoingEdgesRecursive are now private.
    // Their behavior is tested implicitly via persistAndPropagateSkip tests.

    // ═══════════════════════════════════════════════════════════════════════════
    // extractLabel() tests (via persistAndPropagateSkip behavior)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Label extraction")
    class LabelExtractionTests {

        @Test
        @DisplayName("Should extract label from prefixed nodeId")
        void shouldExtractLabelFromPrefixedNodeId() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:my_step");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then - label should be "my_step" not "mcp:my_step"
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> "mcp:my_step".equals(ctx.nodeId()) && "my_step".equals(ctx.nodeLabel())),
                any());
        }

        @Test
        @DisplayName("Should handle nodeId without colon")
        void shouldHandleNodeIdWithoutColon() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("simple_node");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then - label should be the full nodeId
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> "simple_node".equals(ctx.nodeId()) && "simple_node".equals(ctx.nodeLabel())),
                any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Merge node handling tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Merge node handling")
    class MergeNodeHandlingTests {

        @Test
        @DisplayName("Should NOT skip merge node when other predecessor is not yet resolved")
        void shouldNotSkipMergeNodeSuccessor() {
            // Given: skippedNode -> mergeNode (merge has 2 predecessors, only 1 resolved)
            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:wait_all");
            when(mergeNode.isMergeNode()).thenReturn(true);
            // Merge has 2 predecessors: mcp:step1 (will be skipped) and mcp:other_branch (not yet resolved)
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:step1", "mcp:other_branch"));

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(mergeNode));

            // mcp:step1 has a SKIPPED result, mcp:other_branch has no result yet
            when(execution.getStepResult("mcp:step1")).thenReturn(
                StepExecutionResult.skipped("mcp:step1", "skipped"));
            when(execution.getStepResult("mcp:other_branch")).thenReturn(null);

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then: edge to merge is skipped, but merge node itself is NOT completed as skipped
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("mcp:step1"), eq("core:wait_all"), eq(0));
            // completeSkipped called once for skippedNode itself, NOT for mergeNode
            verify(stepCompletionOrchestrator, times(1)).completeSkipped(any(), any());
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> "mcp:step1".equals(ctx.nodeId())), any());
        }

        @Test
        @DisplayName("Should NOT skip implicit merge node when other predecessor is not yet resolved")
        void shouldNotSkipImplicitMergeNodeSuccessor() {
            // Given: skippedNode -> implicitMerge (has 2 predecessors, only 1 resolved)
            BaseNode implicitMerge = mock(BaseNode.class);
            when(implicitMerge.getNodeId()).thenReturn("mcp:final_step");
            when(implicitMerge.isMergeNode()).thenReturn(false);
            when(implicitMerge.isImplicitMerge()).thenReturn(true);
            when(implicitMerge.getPredecessorIds()).thenReturn(List.of("mcp:step1", "mcp:step2"));

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(implicitMerge));

            // mcp:step1 is SKIPPED, mcp:step2 not yet resolved
            when(execution.getStepResult("mcp:step1")).thenReturn(
                StepExecutionResult.skipped("mcp:step1", "skipped"));
            when(execution.getStepResult("mcp:step2")).thenReturn(null);

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then: edge is skipped but merge node itself is NOT skipped
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("mcp:step1"), eq("mcp:final_step"), eq(0));
            verify(stepCompletionOrchestrator, times(1)).completeSkipped(any(), any());
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> "mcp:step1".equals(ctx.nodeId())), any());
        }

        @Test
        @DisplayName("Should skip regular successor but NOT merge in mixed chain (other pred unresolved)")
        void shouldSkipRegularButNotMergeInChain() {
            // Given: skippedNode has 2 successors: regular + merge
            BaseNode regularNode = mock(BaseNode.class);
            when(regularNode.getNodeId()).thenReturn("mcp:step2");
            when(regularNode.isMergeNode()).thenReturn(false);
            when(regularNode.isImplicitMerge()).thenReturn(false);
            when(regularNode.getSuccessors()).thenReturn(List.of());
            when(regularNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:merge");
            when(mergeNode.isMergeNode()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:step1", "mcp:other"));

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(regularNode, mergeNode));

            // mcp:step1 is SKIPPED but mcp:other is not yet resolved
            when(execution.getStepResult("mcp:step1")).thenReturn(
                StepExecutionResult.skipped("mcp:step1", "skipped"));
            when(execution.getStepResult("mcp:other")).thenReturn(null);

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 0, null);

            // Then:
            // - skippedNode itself is completed as skipped (via completeSkipped with SkipContext)
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> "mcp:step1".equals(ctx.nodeId())), any());
            // - regularNode is completed as skipped (via completeSkippedStep, recursive propagation)
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("mcp:step2"), any(), any(), any(), eq(0));
            // - mergeNode is NOT completed as skipped at all
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:merge"), any(), any(), any(), anyInt());

            // Edge to merge is skipped
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("mcp:step1"), eq("core:merge"), eq(0));
        }
    }

        @Test
        @DisplayName("Should skip merge and propagate when ALL predecessors are SKIPPED (fork→2 branches→merge)")
        void shouldSkipMergeWhenAllPredecessorsSkipped() {
            // This test reproduces the exact bug: fork with 2 branches both reaching merge,
            // all skipped due to upstream failure. Before fix, visited set blocked second branch.
            //
            // DAG: forkNode → [branch1 → download] → merge → downstream
            //                  [branch2 → sendEmail] ↗
            //
            // Both branches are skipped → merge should also be skipped → downstream should be skipped.

            // -- downstream node after merge --
            BaseNode downstreamNode = mock(BaseNode.class);
            when(downstreamNode.getNodeId()).thenReturn("core:parallel");
            when(downstreamNode.isMergeNode()).thenReturn(false);
            when(downstreamNode.isImplicitMerge()).thenReturn(false);
            when(downstreamNode.getSuccessors()).thenReturn(List.of());
            when(downstreamNode.getAllChildNodes()).thenReturn(List.of());

            // -- merge node with 2 predecessors --
            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(false);
            when(mergeNode.isImplicitMerge()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of(downstreamNode));
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            // -- branch 1: download → merge --
            BaseNode downloadNode = mock(BaseNode.class);
            when(downloadNode.getNodeId()).thenReturn("core:download");
            when(downloadNode.isMergeNode()).thenReturn(false);
            when(downloadNode.isImplicitMerge()).thenReturn(false);
            when(downloadNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(downloadNode.getAllChildNodes()).thenReturn(List.of());

            // -- branch 2: sendEmail → merge --
            BaseNode sendEmailNode = mock(BaseNode.class);
            when(sendEmailNode.getNodeId()).thenReturn("core:send_email");
            when(sendEmailNode.isMergeNode()).thenReturn(false);
            when(sendEmailNode.isImplicitMerge()).thenReturn(false);
            when(sendEmailNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(sendEmailNode.getAllChildNodes()).thenReturn(List.of());

            // -- fork node with 2 children --
            BaseNode forkNode = mock(BaseNode.class);
            when(forkNode.getNodeId()).thenReturn("core:branch");
            // Fork returns children via getAllChildNodes
            when(forkNode.getAllChildNodes()).thenReturn(List.of(downloadNode, sendEmailNode));

            // Stub execution.getStepResult(): after DFS processes branch 1 (download→skipped),
            // download and send_email will both have SKIPPED results by the time merge is checked.
            // Branch 1 processes first: download gets SKIPPED result.
            // Branch 2 processes second: send_email gets SKIPPED result.
            // When merge checks predecessors, both should be SKIPPED.
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.skipped("core:download", "predecessor skipped"));
            when(execution.getStepResult("core:send_email")).thenReturn(
                StepExecutionResult.skipped("core:send_email", "predecessor skipped"));

            // When: propagate skip from the fork node
            skipPropagationService.persistAndPropagateSkip(
                execution, forkNode, "core:rss_feed", 0, 1, "trigger:start");

            // Then:
            // 1. Both branch nodes are skipped - 2026-05-21 CRITICAL 1: 8-arg
            //    overload threads triggerId so workflow_epochs row lands under
            //    the correct DAG instead of "trigger:default".
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:download"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:send_email"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));

            // 2. Both edges to merge are marked SKIPPED
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("core:download"), eq("core:sync_1"), eq(0));
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("core:send_email"), eq("core:sync_1"), eq(0));

            // 3. Merge itself is skipped (the fix!) and propagation continues to downstream
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));

            // 4. Downstream node after merge is also skipped
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:parallel"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should skip merge via visited set when getStepResult returns null (real bug scenario)")
        void shouldSkipMergeViaVisitedSetWhenStepResultReturnsNull() {
            // This test reproduces the EXACT bug from production logs:
            // - Fork → [branch1: download → sync_1] + [branch2: send_email → sync_1]
            // - Both branches skipped, but execution.getStepResult() returns NULL for both
            //   because completeSkippedStep() writes to DB/StateSnapshot but NOT to
            //   the in-memory WorkflowExecution.stepResults map.
            // - Before the visited-set fix, both branches saw allResolved=false → merge stuck.
            // - After fix: visited set is checked alongside getStepResult().

            // -- downstream node after merge --
            BaseNode downstreamNode = mock(BaseNode.class);
            when(downstreamNode.getNodeId()).thenReturn("core:parallel");
            when(downstreamNode.isMergeNode()).thenReturn(false);
            when(downstreamNode.isImplicitMerge()).thenReturn(false);
            when(downstreamNode.getSuccessors()).thenReturn(List.of());
            when(downstreamNode.getAllChildNodes()).thenReturn(List.of());

            // -- merge node with 2 predecessors --
            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(false);
            when(mergeNode.isImplicitMerge()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of(downstreamNode));
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            // -- branch 1: download → merge --
            BaseNode downloadNode = mock(BaseNode.class);
            when(downloadNode.getNodeId()).thenReturn("core:download");
            when(downloadNode.isMergeNode()).thenReturn(false);
            when(downloadNode.isImplicitMerge()).thenReturn(false);
            when(downloadNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(downloadNode.getAllChildNodes()).thenReturn(List.of());

            // -- branch 2: sendEmail → merge --
            BaseNode sendEmailNode = mock(BaseNode.class);
            when(sendEmailNode.getNodeId()).thenReturn("core:send_email");
            when(sendEmailNode.isMergeNode()).thenReturn(false);
            when(sendEmailNode.isImplicitMerge()).thenReturn(false);
            when(sendEmailNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(sendEmailNode.getAllChildNodes()).thenReturn(List.of());

            // -- fork node with 2 children --
            BaseNode forkNode = mock(BaseNode.class);
            when(forkNode.getNodeId()).thenReturn("core:branch");
            when(forkNode.getAllChildNodes()).thenReturn(List.of(downloadNode, sendEmailNode));

            // KEY: execution.getStepResult() returns NULL for both predecessors.
            // This is the real bug - the in-memory map is never updated during skip propagation.
            // The visited set is the ONLY way to detect they were skipped.
            when(execution.getStepResult("core:download")).thenReturn(null);
            when(execution.getStepResult("core:send_email")).thenReturn(null);

            // When: propagate skip from the fork node
            skipPropagationService.persistAndPropagateSkip(
                execution, forkNode, "core:rss_feed", 0, 1, "trigger:start");

            // Then:
            // 1. Both branch nodes are skipped - 2026-05-21 CRITICAL 1: 8-arg threads triggerId.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:download"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:send_email"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));

            // 2. Both edges to merge are marked SKIPPED
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("core:download"), eq("core:sync_1"), eq(0));
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("core:send_email"), eq("core:sync_1"), eq(0));

            // 3. Merge itself IS skipped (via visited set detection) and propagation continues
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));

            // 4. Downstream node after merge is also skipped
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:parallel"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should NOT skip merge when one predecessor is COMPLETED (mixed resolution)")
        void shouldNotSkipMergeWhenOnePredecessorCompleted() {
            // DAG: [branch1 → completed_step] → merge
            //      [branch2 → skipped_step]   ↗
            // Merge should NOT be skipped - it should execute normally.

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(false);
            when(mergeNode.isImplicitMerge()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:completed_step", "core:skipped_step"));

            // -- branch 2: skipped_step → merge --
            BaseNode skippedBranch = mock(BaseNode.class);
            when(skippedBranch.getNodeId()).thenReturn("core:skipped_step");
            when(skippedBranch.isMergeNode()).thenReturn(false);
            when(skippedBranch.isImplicitMerge()).thenReturn(false);
            when(skippedBranch.getSuccessors()).thenReturn(List.of(mergeNode));
            when(skippedBranch.getAllChildNodes()).thenReturn(List.of());

            BaseNode sourceNode = mock(BaseNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:source");
            when(sourceNode.getSuccessors()).thenReturn(List.of(skippedBranch));

            // completed_step has COMPLETED result, skipped_step has SKIPPED result
            when(execution.getStepResult("core:completed_step")).thenReturn(
                StepExecutionResult.success("core:completed_step", java.util.Map.of(), 100));
            when(execution.getStepResult("core:skipped_step")).thenReturn(
                StepExecutionResult.skipped("core:skipped_step", "predecessor skipped"));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, sourceNode, "core:decision", 0, 1, "trigger:start");

            // Then: edge to merge is marked SKIPPED, but merge node itself is NOT skipped
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("core:skipped_step"), eq("core:sync_1"), eq(0));
            // Merge should NOT be completed as skipped - it has a COMPLETED predecessor
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), anyInt(), anyInt());
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // markEdgeToMergeSkipped() tests (called from UnifiedExecutionEngine on FAILED nodes)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markEdgeToMergeSkipped() - engine path for FAILED nodes")
    class MarkEdgeToMergeSkippedTests {

        @Test
        @DisplayName("Should skip merge when all predecessors FAILED (none COMPLETED)")
        void shouldSkipMergeWhenAllPredecessorsFailed() {
            // DAG: fork → [branch1: download(FAILED)] → merge → downstream
            //              [branch2: send_email(FAILED)] ↗
            // Both predecessors FAILED → merge should be SKIPPED

            BaseNode downstreamNode = mock(BaseNode.class);
            when(downstreamNode.getNodeId()).thenReturn("mcp:final");
            when(downstreamNode.isMergeNode()).thenReturn(false);
            when(downstreamNode.isImplicitMerge()).thenReturn(false);
            when(downstreamNode.getSuccessors()).thenReturn(List.of());
            when(downstreamNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of(downstreamNode));
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            // Both predecessors FAILED
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException("Connection refused"), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(
                StepExecutionResult.failure("core:send_email", "SMTP error", new RuntimeException("SMTP error"), 300));

            // When: engine calls markEdgeToMergeSkipped for the second failed predecessor
            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "core:send_email", 0, 1, "trigger:start");

            // Then: merge is skipped and propagation continues to downstream.
            // markEdgeToMergeSkipped no longer marks the direct failed→merge edge here -
            // EdgeStatusEmitter.emitOutgoingEdges (failure branch) already did that on the
            // emitNodeComplete path before cascade ran. Re-marking would double-increment
            // state_snapshot.edges (see prod incident run_<id>).
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("core:send_email"), eq("core:sync_1"), eq(0));
            // 2026-05-21 CRITICAL 1 - 8-arg overload threads triggerId.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
            // Downstream also skipped
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("mcp:final"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should skip merge when predecessors are mix of FAILED + SKIPPED (none COMPLETED)")
        void shouldSkipMergeWhenPredecessorsFailedAndSkipped() {
            // DAG: decision → [if: api_call → download(FAILED)] → merge
            //                 [else: send_email(SKIPPED)]        ↗
            // download=FAILED, send_email=SKIPPED → merge should be SKIPPED

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of());
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            // download FAILED, send_email SKIPPED
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException("Connection refused"), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(
                StepExecutionResult.skipped("core:send_email", "branch not selected"));

            // When: engine calls after download fails
            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "core:download", 0, 1, "trigger:start");

            // Then: merge is skipped - 2026-05-21 CRITICAL 1: 8-arg threads triggerId.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should NOT skip merge when one predecessor COMPLETED (via engine path)")
        void shouldNotSkipMergeWhenOnePredecessorCompletedViaEngine() {
            // DAG: fork → [branch1: api_call(COMPLETED)] → merge
            //              [branch2: download(FAILED)]    ↗
            // api_call=COMPLETED → merge should execute normally, NOT be skipped

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:api_call", "core:download"));

            // api_call COMPLETED, download FAILED
            when(execution.getStepResult("mcp:api_call")).thenReturn(
                StepExecutionResult.success("mcp:api_call", java.util.Map.of("result", "ok"), 200));
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException("Connection refused"), 500));

            // When: engine calls after download fails
            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "core:download", 0, 1, "trigger:start");

            // Then: merge is NOT skipped (one predecessor COMPLETED).
            // Edge is also NOT marked here - EdgeStatusEmitter handles direct edges.
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("core:download"), eq("core:sync_1"), eq(0));
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should NOT skip merge when not all predecessors resolved yet (via engine path)")
        void shouldNotSkipMergeWhenNotAllResolvedViaEngine() {
            // DAG: fork → [branch1: download(FAILED)]     → merge
            //              [branch2: send_email(running)]  ↗
            // Only download resolved → merge should wait

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));

            // download FAILED, send_email still running (no result)
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException("Connection refused"), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(null);

            // When
            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "core:download", 0, 1, "trigger:start");

            // Then: merge NOT skipped (waiting for send_email).
            // Edge is also NOT marked here - EdgeStatusEmitter owns direct-edge marking.
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("core:download"), eq("core:sync_1"), eq(0));
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), anyInt(), anyInt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DFS path: merge with FAILED predecessors (handleMergeNodeSuccessor)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DFS path - merge with FAILED predecessors")
    class DfsMergeWithFailedPredecessorsTests {

        @Test
        @DisplayName("Should skip merge when one predecessor FAILED and other SKIPPED in DFS")
        void shouldSkipMergeWhenPredecessorFailedAndOtherSkippedInDfs() {
            // DAG: fork → [branch1: download (FAILED in stepResult)] → merge → downstream
            //              [branch2: send_email (being SKIPPED in DFS)] ↗
            // When DFS reaches merge via send_email, download is already FAILED.
            // Since no predecessor COMPLETED → merge should be SKIPPED.

            BaseNode downstreamNode = mock(BaseNode.class);
            when(downstreamNode.getNodeId()).thenReturn("mcp:final");
            when(downstreamNode.isMergeNode()).thenReturn(false);
            when(downstreamNode.isImplicitMerge()).thenReturn(false);
            when(downstreamNode.getSuccessors()).thenReturn(List.of());
            when(downstreamNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of(downstreamNode));
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            // send_email → merge (DFS propagates skip through send_email)
            BaseNode sendEmailNode = mock(BaseNode.class);
            when(sendEmailNode.getNodeId()).thenReturn("core:send_email");
            when(sendEmailNode.isMergeNode()).thenReturn(false);
            when(sendEmailNode.isImplicitMerge()).thenReturn(false);
            when(sendEmailNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(sendEmailNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode sourceNode = mock(BaseNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:source");
            when(sourceNode.getSuccessors()).thenReturn(List.of(sendEmailNode));

            // download FAILED (already in stepResult), send_email not in stepResult (DFS will visit it)
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException("Connection refused"), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(null);

            // When: DFS propagates skip from sourceNode through send_email to merge
            skipPropagationService.persistAndPropagateSkip(
                execution, sourceNode, "core:decision", 0, 1, "trigger:start");

            // Then: send_email is skipped (DFS), merge is also skipped (FAILED + SKIPPED = no COMPLETED)
            // 2026-05-21 CRITICAL 1 - 8-arg overload threads triggerId.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:send_email"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
            // Downstream also skipped
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("mcp:final"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should NOT skip merge in DFS when one predecessor COMPLETED")
        void shouldNotSkipMergeInDfsWhenOnePredecessorCompleted() {
            // DAG: fork → [branch1: api_call (COMPLETED)] → merge
            //              [branch2: other (being SKIPPED)] ↗
            // Merge should NOT be skipped - api_call COMPLETED.

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:api_call", "core:other"));

            BaseNode otherNode = mock(BaseNode.class);
            when(otherNode.getNodeId()).thenReturn("core:other");
            when(otherNode.isMergeNode()).thenReturn(false);
            when(otherNode.isImplicitMerge()).thenReturn(false);
            when(otherNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(otherNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode sourceNode = mock(BaseNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:source");
            when(sourceNode.getSuccessors()).thenReturn(List.of(otherNode));

            // api_call COMPLETED, other not in stepResult (being skipped in DFS)
            when(execution.getStepResult("mcp:api_call")).thenReturn(
                StepExecutionResult.success("mcp:api_call", java.util.Map.of(), 200));
            when(execution.getStepResult("core:other")).thenReturn(null);

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, sourceNode, "core:decision", 0, 1, "trigger:start");

            // Then: merge is NOT skipped (api_call COMPLETED)
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:sync_1"), any(), any(), any(), anyInt(), anyInt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // In-memory state sync tests (root-cause fix: execution.setStepResult)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("In-memory state sync (execution.setStepResult)")
    class InMemoryStateSyncTests {

        @Test
        @DisplayName("persistAndPropagateSkip should call execution.setStepResult for the skipped node")
        void shouldSetStepResultOnPersistAndPropagateSkip() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 1, "trigger:start");

            // Then: execution.setStepResult must be called with SKIPPED status
            verify(execution).setStepResult(eq("mcp:step1"), argThat(result ->
                result.status() == NodeStatus.SKIPPED && result.stepId().equals("mcp:step1")));
        }

        @Test
        @DisplayName("emitSkipAndPropagate should call execution.setStepResult for each descendant")
        void shouldSetStepResultForDescendants() {
            // Given: skippedNode → successor1 → successor2
            BaseNode successor2 = mock(BaseNode.class);
            when(successor2.getNodeId()).thenReturn("mcp:step3");
            when(successor2.isMergeNode()).thenReturn(false);
            when(successor2.isImplicitMerge()).thenReturn(false);
            when(successor2.getSuccessors()).thenReturn(List.of());
            when(successor2.getAllChildNodes()).thenReturn(List.of());

            BaseNode successor1 = mock(BaseNode.class);
            when(successor1.getNodeId()).thenReturn("mcp:step2");
            when(successor1.isMergeNode()).thenReturn(false);
            when(successor1.isImplicitMerge()).thenReturn(false);
            when(successor1.getSuccessors()).thenReturn(List.of(successor2));
            when(successor1.getAllChildNodes()).thenReturn(List.of());

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(successor1));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 0, 1, "trigger:start");

            // Then: setStepResult called for all 3 nodes (source + 2 descendants)
            verify(execution).setStepResult(eq("mcp:step1"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
            verify(execution).setStepResult(eq("mcp:step2"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
            verify(execution).setStepResult(eq("mcp:step3"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("checkAndSkipMergeIfNoSuccessfulPredecessor should call execution.setStepResult for merge")
        void shouldSetStepResultForMergeViaEnginePath() {
            // DAG: fork → [branch1: download(FAILED)] → merge
            //              [branch2: send_email(SKIPPED)] ↗
            // Engine calls markEdgeToMergeSkipped for download → merge should be SKIPPED

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of());
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException(), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(
                StepExecutionResult.skipped("core:send_email", "branch not selected"));

            // When: engine path
            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "core:download", 0, 1, "trigger:start");

            // Then: setStepResult called for merge with SKIPPED
            verify(execution).setStepResult(eq("core:sync_1"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("handleMergeNodeSuccessor should call execution.setStepResult for merge in DFS path")
        void shouldSetStepResultForMergeViaDfsPath() {
            // DAG: fork → [branch1: download(FAILED)] → merge → downstream
            //              [branch2: send_email(being SKIPPED in DFS)] ↗
            // DFS reaches merge via send_email, download already FAILED → merge SKIPPED

            BaseNode downstreamNode = mock(BaseNode.class);
            when(downstreamNode.getNodeId()).thenReturn("mcp:final");
            when(downstreamNode.isMergeNode()).thenReturn(false);
            when(downstreamNode.isImplicitMerge()).thenReturn(false);
            when(downstreamNode.getSuccessors()).thenReturn(List.of());
            when(downstreamNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.isMergeNode()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of(downstreamNode));
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode sendEmailNode = mock(BaseNode.class);
            when(sendEmailNode.getNodeId()).thenReturn("core:send_email");
            when(sendEmailNode.isMergeNode()).thenReturn(false);
            when(sendEmailNode.isImplicitMerge()).thenReturn(false);
            when(sendEmailNode.getSuccessors()).thenReturn(List.of(mergeNode));
            when(sendEmailNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode sourceNode = mock(BaseNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:source");
            when(sourceNode.getSuccessors()).thenReturn(List.of(sendEmailNode));

            // download FAILED, send_email not yet in stepResult (DFS will skip it)
            when(execution.getStepResult("core:download")).thenReturn(
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException(), 500));
            when(execution.getStepResult("core:send_email")).thenReturn(null);

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, sourceNode, "core:decision", 0, 1, "trigger:start");

            // Then: setStepResult called for send_email, merge, and downstream
            verify(execution).setStepResult(eq("core:send_email"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
            verify(execution).setStepResult(eq("core:sync_1"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
            verify(execution).setStepResult(eq("mcp:final"), argThat(r ->
                r.status() == NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("Cross-path: DFS skips branch, then engine detects merge should skip via in-memory state")
        void crossPathDfsThenEngineViaInMemoryState() {
            // This is THE production bug scenario:
            // 1. Decision takes "if" branch → EdgeStatusEmitter DFS-skips "else" branch (send_email)
            //    → DFS calls execution.setStepResult("core:send_email", SKIPPED) ← THE FIX
            // 2. "if" branch (download) FAILS → engine calls markEdgeToMergeSkipped
            //    → checkAndSkipMergeIfNoSuccessfulPredecessor checks execution.getStepResult("core:send_email")
            //    → NOW returns SKIPPED (instead of null) → allResolved=true, anyCompleted=false → merge SKIPPED
            //
            // Use a mock execution with a real backing map to simulate in-memory state.

            // Backing map to make setStepResult/getStepResult work like a real WorkflowExecution
            Map<String, StepExecutionResult> stepResults = new HashMap<>();
            WorkflowExecution statefulExecution = mock(WorkflowExecution.class);
            doAnswer(inv -> {
                stepResults.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(statefulExecution).setStepResult(anyString(), any(StepExecutionResult.class));
            when(statefulExecution.getStepResult(anyString())).thenAnswer(inv ->
                stepResults.get(inv.getArgument(0, String.class)));

            // Step 1: DFS skips send_email (simulates EdgeStatusEmitter → persistAndPropagateSkip)
            BaseNode sendEmailNode = mock(BaseNode.class);
            when(sendEmailNode.getNodeId()).thenReturn("core:send_email");
            when(sendEmailNode.getSuccessors()).thenReturn(List.of());

            skipPropagationService.persistAndPropagateSkip(
                statefulExecution, sendEmailNode, "core:decision", 0, 1, "trigger:start");

            // Verify: in-memory state now has send_email as SKIPPED
            StepExecutionResult sendEmailResult = stepResults.get("core:send_email");
            assertNotNull(sendEmailResult, "send_email should have SKIPPED result in-memory after DFS");
            assertEquals(NodeStatus.SKIPPED, sendEmailResult.status());

            // Step 2: download FAILS → engine calls markEdgeToMergeSkipped
            stepResults.put("core:download",
                StepExecutionResult.failure("core:download", "Connection refused", new RuntimeException(), 500));

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:sync_1");
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("core:download", "core:send_email"));
            when(mergeNode.getSuccessors()).thenReturn(List.of());
            when(mergeNode.getAllChildNodes()).thenReturn(List.of());

            skipPropagationService.markEdgeToMergeSkipped(
                statefulExecution, mergeNode, "core:download", 0, 1, "trigger:start");

            // Verify: merge is now SKIPPED in-memory
            StepExecutionResult mergeResult = stepResults.get("core:sync_1");
            assertNotNull(mergeResult, "merge should have SKIPPED result in-memory after engine path");
            assertEquals(NodeStatus.SKIPPED, mergeResult.status());

            // Verify: completeSkippedStep was called for the merge - 2026-05-21 CRITICAL 1.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(statefulExecution), eq("core:sync_1"), any(), any(), any(), eq(0), eq(1), eq("trigger:start"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Item index handling tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Item index handling")
    class ItemIndexHandlingTests {

        @Test
        @DisplayName("Should pass item index to edge status service")
        void shouldPassItemIndexToEdgeStatusService() {
            // Given
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");
            when(successor.getSuccessors()).thenReturn(List.of());

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of(successor));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 5, 0, null);

            // Then
            verify(edgeStatusService).markEdgeSkipped(any(), any(), any(), eq(5));
        }

        @Test
        @DisplayName("Should pass item index to step completion orchestrator")
        void shouldPassItemIndexToStepCompletionOrchestrator() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:step1");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "core:decision", 3, 0, null);

            // Then
            verify(stepCompletionOrchestrator).completeSkipped(
                argThat(ctx -> ctx.itemIndex() == 3),
                any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // perItemScope (split context) tests - regression for Gmail Auto-Labeler bug.
    //
    // Scenario: classify inside a split routes some items to a port with no
    // outgoing edge. Without perItemScope, completeSkipped would add the
    // downstream apply_X nodes to EpochState.skippedNodeIds globally,
    // blocking sibling items that selected a wired branch.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("persistAndPropagateSkip() with perItemScope")
    class PerItemScopeTests {

        @Test
        @DisplayName("perItemScope=true uses completeSkippedStepWithoutStateUpdate (no EpochState mutation)")
        void perItemScopeUsesWithoutStateUpdateVariant() {
            // Given - a classify-successor node skipped for a single split item
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:apply_tech");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "agent:classify", 3, 1, "trigger:cron", true);

            // Then - per-item path only: no global state mutation. Production now calls the
            // (epoch, triggerId)-aware overload so per-epoch counts land under (epoch=1, "trigger:cron")
            // instead of the legacy (epoch=0, "trigger:default") bucket.
            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution), eq("mcp:apply_tech"), eq("apply_tech"),
                anyString(), eq("agent:classify"), eq(3), eq(1), eq("trigger:cron"));
            verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
            verify(execution, never()).setStepResult(anyString(), any());
        }

        @Test
        @DisplayName("perItemScope=false (default) still uses completeSkipped and setStepResult - back-compat")
        void perItemScopeFalseKeepsGlobalBehavior() {
            // Given
            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:apply_tech");
            when(skippedNode.getSuccessors()).thenReturn(List.of());

            // When - explicit false
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "agent:classify", 3, 1, "trigger:cron", false);

            // Then - global path
            verify(stepCompletionOrchestrator).completeSkipped(any(), eq("trigger:cron"));
            verify(stepCompletionOrchestrator, never()).completeSkippedStepWithoutStateUpdate(
                any(), anyString(), anyString(), anyString(), anyString(), anyInt());
            verify(stepCompletionOrchestrator, never()).completeSkippedStepWithoutStateUpdate(
                any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any());
            verify(execution).setStepResult(eq("mcp:apply_tech"), any());
        }

        @Test
        @DisplayName("perItemScope propagates into recursion - second-level skip also per-item")
        void perItemScopePropagatesIntoRecursion() {
            // Given - apply_tech → record_tech chain, apply_tech skipped per-item
            BaseNode recordTech = mock(BaseNode.class);
            when(recordTech.getNodeId()).thenReturn("table:record_tech");
            when(recordTech.getSuccessors()).thenReturn(List.of());

            BaseNode applyTech = mock(BaseNode.class);
            when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");
            when(applyTech.getSuccessors()).thenReturn(List.of(recordTech));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, applyTech, "agent:classify", 3, 1, "trigger:cron", true);

            // Then - BOTH apply_tech and record_tech use the without-state-update variant.
            // Before the fix, only the first level was per-item and the recursion
            // re-poisoned EpochState through the standard completeSkippedStep call.
            // Both calls now propagate the (epoch=1, triggerId="trigger:cron") for per-epoch isolation.
            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution), eq("mcp:apply_tech"), anyString(), anyString(), anyString(), eq(3),
                eq(1), eq("trigger:cron"));
            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution), eq("table:record_tech"), anyString(), anyString(), anyString(), eq(3),
                eq(1), eq("trigger:cron"));

            // And NO global-path call anywhere in the chain
            verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), anyString(), anyString(), anyString(), anyString(), anyInt());
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
            verify(execution, never()).setStepResult(anyString(), any());
        }

        @Test
        @DisplayName("Edge SKIPPED is still emitted per item in perItemScope (UI visibility)")
        void edgeSkippedStillEmittedInPerItemScope() {
            // Given
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:apply_tech");
            when(successor.getSuccessors()).thenReturn(List.of());

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("agent:classify");
            when(skippedNode.getSuccessors()).thenReturn(List.of(successor));

            // When
            skipPropagationService.persistAndPropagateSkip(
                execution, skippedNode, "agent:classify", 3, 1, "trigger:cron", true);

            // Then - edge SKIPPED emission is unconditional so the UI can still show
            // "item 3 skipped apply_tech" even though the node stays PENDING globally.
            verify(edgeStatusService).markEdgeSkipped(
                eq(execution), eq("agent:classify"), eq("mcp:apply_tech"), eq(3));
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cascadeFailureToSuccessors() tests - single shared cascade entry point
    // for both sync (UnifiedExecutionEngine) and async (AgentAsyncCompletionService).
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cascadeFailureToSuccessors()")
    class CascadeFailureToSuccessorsTests {

        @Test
        @DisplayName("Test 12: increments orchestrator.skip.cascade.descendants counter and records timer with source tag")
        void cascadeIncrementsCounterAndRecordsTimerForBothSources() {
            // Two regular successors, simulating a typical async-fail-with-2-descendants shape.
            BaseNode succ1 = mock(BaseNode.class);
            when(succ1.getNodeId()).thenReturn("mcp:downstream_a");
            when(succ1.getSuccessors()).thenReturn(List.of());
            BaseNode succ2 = mock(BaseNode.class);
            when(succ2.getNodeId()).thenReturn("mcp:downstream_b");
            when(succ2.getSuccessors()).thenReturn(List.of());

            BaseNode failedNode = mock(BaseNode.class);
            when(failedNode.getNodeId()).thenReturn("agent:analyze");
            when(failedNode.getSuccessors()).thenReturn(List.of(succ1, succ2));

            // Async cascade - counter and timer should record source=async.
            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedNode, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            assertEquals(2.0, meterRegistry
                .counter("orchestrator.skip.cascade.descendants", "source", "async").count(), 0.001);
            assertEquals(1, meterRegistry
                .timer("orchestrator.skip.cascade.duration", "source", "async").count());

            // Sync cascade on a separate failed node - distinct counter under source=sync.
            BaseNode succ3 = mock(BaseNode.class);
            when(succ3.getNodeId()).thenReturn("mcp:downstream_c");
            when(succ3.getSuccessors()).thenReturn(List.of());
            BaseNode failedNode2 = mock(BaseNode.class);
            when(failedNode2.getNodeId()).thenReturn("mcp:fetch_emails");
            when(failedNode2.getSuccessors()).thenReturn(List.of(succ3));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedNode2, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_SYNC);

            assertEquals(1.0, meterRegistry
                .counter("orchestrator.skip.cascade.descendants", "source", "sync").count(), 0.001);
            // async counter unchanged.
            assertEquals(2.0, meterRegistry
                .counter("orchestrator.skip.cascade.descendants", "source", "async").count(), 0.001);
        }

        @Test
        @DisplayName("Test 8 (routine side): zero successors is a no-op - no counter increment, no DB writes")
        void cascadeOnTerminalNodeWithZeroSuccessorsIsNoOp() {
            BaseNode terminal = mock(BaseNode.class);
            when(terminal.getNodeId()).thenReturn("interface:dashboard");
            when(terminal.getSuccessors()).thenReturn(List.of());

            skipPropagationService.cascadeFailureToSuccessors(
                execution, terminal, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            assertEquals(0.0, meterRegistry
                .counter("orchestrator.skip.cascade.descendants", "source", "async").count(), 0.001);
            // Timer still records the (near-zero) cascade duration - measures the call site itself.
            assertEquals(1, meterRegistry
                .timer("orchestrator.skip.cascade.duration", "source", "async").count());

            verifyNoInteractions(stepCompletionOrchestrator);
            verifyNoInteractions(edgeStatusService);
        }

        @Test
        @DisplayName("perItemScope=true cascades through port children when getSuccessors is empty")
        void perItemCascadeUsesAllChildNodesForBranchingFailedNode() {
            BaseNode clearedNote = mock(BaseNode.class);
            when(clearedNote.getNodeId()).thenReturn("core:cleared_note");
            when(clearedNote.getAllChildNodes()).thenReturn(List.of());
            when(clearedNote.getSuccessors()).thenReturn(List.of());

            BaseNode flaggedNote = mock(BaseNode.class);
            when(flaggedNote.getNodeId()).thenReturn("core:flagged_note");
            when(flaggedNote.getAllChildNodes()).thenReturn(List.of());
            when(flaggedNote.getSuccessors()).thenReturn(List.of());

            ExecutionNode failedGuardrail = mock(ExecutionNode.class);
            when(failedGuardrail.getNodeId()).thenReturn("agent:compliance_screen");
            when(failedGuardrail.getSuccessors()).thenReturn(List.of());
            when(failedGuardrail.getAllChildNodes()).thenReturn(List.of(clearedNote, flaggedNote));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedGuardrail, 4, 6, "trigger:review", true,
                V2SkipPropagationService.SOURCE_ASYNC);

            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution), eq("core:cleared_note"), eq("cleared_note"),
                contains("agent:compliance_screen"), eq("agent:compliance_screen"),
                eq(4), eq(6), eq("trigger:review"));
            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution), eq("core:flagged_note"), eq("flagged_note"),
                contains("agent:compliance_screen"), eq("agent:compliance_screen"),
                eq(4), eq(6), eq("trigger:review"));
            verifyNoInteractions(edgeStatusService);
        }

        @Test
        @DisplayName("perItemScope=false keeps direct-successor-only cascade to avoid branching double counts")
        void globalCascadeDoesNotUseAllChildNodesForBranchingFailedNode() {
            BaseNode clearedNote = mock(BaseNode.class);
            when(clearedNote.getNodeId()).thenReturn("core:cleared_note");

            ExecutionNode failedGuardrail = mock(ExecutionNode.class);
            when(failedGuardrail.getNodeId()).thenReturn("agent:compliance_screen");
            when(failedGuardrail.getSuccessors()).thenReturn(List.of());
            when(failedGuardrail.getAllChildNodes()).thenReturn(List.of(clearedNote));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedGuardrail, 4, 6, "trigger:review", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            verify(failedGuardrail, never()).getAllChildNodes();
            verifyNoInteractions(stepCompletionOrchestrator);
            verifyNoInteractions(edgeStatusService);
        }

        @Test
        @DisplayName("Null failedNode short-circuits without throwing")
        void cascadeWithNullFailedNodeIsNoOpWithoutThrowing() {
            // Should not NPE - guard at the top of cascadeFailureToSuccessors.
            assertDoesNotThrow(() -> skipPropagationService.cascadeFailureToSuccessors(
                execution, null, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC));

            verifyNoInteractions(stepCompletionOrchestrator);
            verifyNoInteractions(edgeStatusService);
        }

        @Test
        @DisplayName("Test 5: merge with one COMPLETED predecessor stays alive (NOT pre-skipped)")
        void cascadeUpstreamOfMergeWithLiveBranchDoesNotPreSkipMerge() {
            // Setup: failed node → merge ← live (COMPLETED) sibling branch.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:wait_all");
            when(merge.isMergeNode()).thenReturn(true);
            when(merge.getPredecessorIds()).thenReturn(List.of("agent:analyze", "mcp:other_branch"));

            BaseNode failedNode = mock(BaseNode.class);
            when(failedNode.getNodeId()).thenReturn("agent:analyze");
            when(failedNode.getSuccessors()).thenReturn(List.of(merge));

            // Other branch is COMPLETED - merge should NOT be pre-skipped.
            when(execution.getStepResult("agent:analyze")).thenReturn(
                StepExecutionResult.skipped("agent:analyze", "fail"));
            when(execution.getStepResult("mcp:other_branch")).thenReturn(
                StepExecutionResult.success("mcp:other_branch", Map.of(), 100L));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedNode, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            // The cascade no longer re-marks the direct failed→merge edge - that's
            // EdgeStatusEmitter.emitOutgoingEdges' job (failure branch), invoked on the
            // emitNodeComplete path before cascade runs. Re-marking here used to cause
            // a double-increment on the same (edge, itemIndex, iteration) key in
            // state_snapshot.edges (prod incident run_<id>).
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("agent:analyze"), eq("core:wait_all"), eq(0));
            // The merge itself is NOT marked SKIPPED - its other branch is alive.
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), eq("core:wait_all"), any(), any(), any(), anyInt(), anyInt());
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), eq("core:wait_all"), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Test 13: 4-site setStepResult guard prevents clobbering a concurrent COMPLETED merge result")
        void mergeSkipSetStepResultGuardPreventsClobberAt475() {
            // Test the :475 guard: handleMergeNodeSuccessor 2nd convergence path.
            // Setup: skip cascade reaches a merge whose all predecessors are resolved
            // and none completed - but a concurrent thread already wrote a stepResult
            // for the merge (e.g. it was COMPLETED via another path). The guard must
            // skip the in-memory setStepResult to avoid clobbering.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:wait_all");
            when(merge.isMergeNode()).thenReturn(false);
            when(merge.isImplicitMerge()).thenReturn(true);
            when(merge.getPredecessorIds()).thenReturn(List.of("agent:analyze", "agent:other"));
            when(merge.getSuccessors()).thenReturn(List.of());

            BaseNode failedNode = mock(BaseNode.class);
            when(failedNode.getNodeId()).thenReturn("agent:analyze");
            when(failedNode.getSuccessors()).thenReturn(List.of(merge));

            // Both predecessors are SKIPPED in the eyes of the analyzer, BUT a concurrent
            // path wrote a COMPLETED stepResult for the merge itself. The guard must NOT
            // overwrite it with a SKIPPED value.
            when(execution.getStepResult("agent:analyze")).thenReturn(
                StepExecutionResult.skipped("agent:analyze", "fail"));
            when(execution.getStepResult("agent:other")).thenReturn(
                StepExecutionResult.skipped("agent:other", "fail"));
            // Crucial: pre-existing stepResult for the merge.
            when(execution.getStepResult("core:wait_all")).thenReturn(
                StepExecutionResult.success("core:wait_all", Map.of("preExisting", true), 100L));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, failedNode, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            // Direct failed→merge edge is NOT marked here - EdgeStatusEmitter owns
            // that on the emitNodeComplete path; cascade only does the convergence check.
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("agent:analyze"), eq("core:wait_all"), eq(0));
            // The guard protects the in-memory map from clobber: setStepResult on the
            // merge node must NOT be called with a SKIPPED value (would erase the
            // pre-existing COMPLETED stepResult).
            verify(execution, never()).setStepResult(eq("core:wait_all"), argThat(r ->
                r != null && r.status() == NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("Test 6: parallel branches both fail converging on same merge - merge skipped exactly once")
        void parallelBranchesBothFailConvergingMergeSkippedExactlyOnce() {
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(merge.isMergeNode()).thenReturn(true);
            when(merge.isImplicitMerge()).thenReturn(false);
            when(merge.getPredecessorIds()).thenReturn(List.of("mcp:branch_a", "mcp:branch_b"));
            when(merge.getSuccessors()).thenReturn(List.of());

            BaseNode branchA = mock(BaseNode.class);
            when(branchA.getNodeId()).thenReturn("mcp:branch_a");
            when(branchA.getSuccessors()).thenReturn(List.of(merge));
            BaseNode branchB = mock(BaseNode.class);
            when(branchB.getNodeId()).thenReturn("mcp:branch_b");
            when(branchB.getSuccessors()).thenReturn(List.of(merge));

            // First cascade: branchA fails → only its edge is marked SKIPPED, merge waits.
            when(execution.getStepResult("mcp:branch_a")).thenReturn(
                StepExecutionResult.skipped("mcp:branch_a", "fail"));
            when(execution.getStepResult("mcp:branch_b")).thenReturn(null); // not yet resolved

            skipPropagationService.cascadeFailureToSuccessors(
                execution, branchA, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), eq("core:join"), any(), any(), any(), anyInt(), anyInt());

            // Now branchB fails - both predecessors resolved, none completed → merge gets SKIPPED.
            when(execution.getStepResult("mcp:branch_b")).thenReturn(
                StepExecutionResult.skipped("mcp:branch_b", "fail"));

            skipPropagationService.cascadeFailureToSuccessors(
                execution, branchB, 0, 5, "trigger:cron", false,
                V2SkipPropagationService.SOURCE_ASYNC);

            // Merge transitions to SKIPPED EXACTLY ONCE across the two cascades - 2026-05-21 CRITICAL 1.
            verify(stepCompletionOrchestrator, times(1)).completeSkippedStep(
                eq(execution), eq("core:join"), any(), any(), any(), eq(0), eq(5), eq("trigger:cron"));
        }

        @Test
        @DisplayName("Regression run_<id>: Fork → (openai OK, gemini FAILED) → Merge - failed→merge edge marked SKIPPED exactly once across emitter+cascade pipeline")
        void prodIncident_failedBranchToMergeEdgeIsRecordedExactlyOnceAcrossEmitterAndCascade() {
            // Reproduces production run_<id> (workflow f084a6f5-…):
            // Fork(generate_both) → [openai_image COMPLETED, gemini_image FAILED] → Merge(collect_results)
            // Pre-fix observation: state_snapshot.edges["mcp:gemini_image->core:collect_results"]
            // = {skipped: 2}. Expected: {skipped: 1}.
            //
            // Two writes hit the same edge key with the same (itemIndex=0, iteration=null):
            //   #1 EdgeStatusEmitter.emitOutgoingEdges (failure branch L267-282) - batched flush.
            //   #2 V2SkipPropagationService.markEdgeToMergeSkipped (formerly L345) - immediate write.
            // EdgeCounts.increment is non-idempotent → DB JSONB shows 2.
            //
            // Post-fix: cascade no longer re-marks the direct edge. Only #1 fires. We assert
            // the cascade does NOT call markEdgeSkipped for the failed→merge edge here, which
            // is the chokepoint that doubled the prod count.
            BaseNode merge = mock(BaseNode.class);
            when(merge.getNodeId()).thenReturn("core:collect_results");
            when(merge.isMergeNode()).thenReturn(true);
            when(merge.isImplicitMerge()).thenReturn(false);
            when(merge.getPredecessorIds()).thenReturn(List.of("mcp:openai_image", "mcp:gemini_image"));
            when(merge.getSuccessors()).thenReturn(List.of());

            BaseNode geminiFailed = mock(BaseNode.class);
            when(geminiFailed.getNodeId()).thenReturn("mcp:gemini_image");
            when(geminiFailed.getSuccessors()).thenReturn(List.of(merge));

            // Sibling branch (openai) is COMPLETED → merge will execute normally,
            // not be pre-skipped by cascade convergence-check.
            when(execution.getStepResult("mcp:openai_image")).thenReturn(
                StepExecutionResult.success("mcp:openai_image", Map.of("image", "<b64>"), 15951L));
            when(execution.getStepResult("mcp:gemini_image")).thenReturn(
                StepExecutionResult.failure("mcp:gemini_image", "Invalid value at 'generation_config'",
                    new RuntimeException("Google API 400"), 126L));

            // Act - exact engine sequence after gemini fails:
            //   (the EdgeStatusEmitter call has already happened upstream - we don't re-run it
            //    here; the assertion below is that cascade does NOT add a second mark)
            skipPropagationService.cascadeFailureToSuccessors(
                execution, geminiFailed, /*itemIndex=*/0, /*epoch=*/1, "trigger:image_prompt",
                /*perItemScope=*/false, V2SkipPropagationService.SOURCE_SYNC);

            // Assert - cascade contributes ZERO calls to markEdgeSkipped for the gemini→merge
            // edge. Pre-fix this would have been 1 (immediate write), causing the prod
            // skipped:2. Post-fix it's 0 - the only writer is EdgeStatusEmitter upstream.
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("mcp:gemini_image"), eq("core:collect_results"), eq(0));
            verify(edgeStatusService, never()).markEdgeSkipped(
                eq(execution), eq("mcp:gemini_image"), eq("core:collect_results"), eq(0), any());

            // Convergence-check correctly leaves the merge alone (one predecessor COMPLETED).
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                any(), eq("core:collect_results"), any(), any(), any(), anyInt(), anyInt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2026-05-21 CRITICAL 1 - triggerId propagation through global skip-cascade
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Regression test for the e2e audit (2026-05-21) CRITICAL 1: the global
     * skip-cascade path called the 7-arg {@code completeSkippedStep} overload
     * which delegates to {@code completeSkipped(ctx)} → drops triggerId →
     * {@code workflow_epochs} row lands under {@code "trigger:default"}.
     *
     * <p>Three call sites in V2SkipPropagationService were affected:
     * <ol>
     *   <li>{@code emitSkipAndPropagate:561} - global descendant skip</li>
     *   <li>{@code checkAndSkipMergeIfNoSuccessfulPredecessor:406} - merge skip</li>
     *   <li>{@code markEdgeToMergeSkipped:506} - merge convergence skip</li>
     * </ol>
     *
     * <p>Each was switched to call the new 8-arg overload that threads triggerId
     * through to {@code completeSkipped(ctx, triggerId)} → writes the row under
     * the correct DAG. The tests above already pin the 8-arg signature for each
     * call site by changing the existing verify() matchers - this nested class
     * adds explicit negative assertions to catch a future regression that would
     * silently fall back to the 7-arg overload.
     */
    @Nested
    @DisplayName("CRITICAL 1 (2026-05-21) - global skip-cascade threads triggerId, never drops to trigger:default")
    class TriggerIdPropagationRegression {

        @Test
        @DisplayName("emitSkipAndPropagate: 8-arg overload is called, 7-arg form is NEVER reached (would drop triggerId)")
        void emitSkipAndPropagateUsesTriggerIdAwareOverload() {
            // Setup a minimal DAG: source → leafNode (no successors, no merge).
            BaseNode leafNode = mock(BaseNode.class);
            when(leafNode.getNodeId()).thenReturn("mcp:leaf");
            when(leafNode.isMergeNode()).thenReturn(false);
            when(leafNode.isImplicitMerge()).thenReturn(false);
            when(leafNode.getSuccessors()).thenReturn(List.of());
            when(leafNode.getAllChildNodes()).thenReturn(List.of());

            BaseNode sourceNode = mock(BaseNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:source");
            when(sourceNode.getSuccessors()).thenReturn(List.of(leafNode));

            when(execution.getStepResult("mcp:leaf")).thenReturn(null);

            // Trigger global cascade (perItemScope=false) with triggerId="trigger:start".
            skipPropagationService.persistAndPropagateSkip(
                execution, sourceNode, "mcp:source", 0, 7, "trigger:start");

            // POST-FIX: 8-arg overload called with the actual triggerId.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("mcp:leaf"), any(), any(), any(),
                eq(0), eq(7), eq("trigger:start"));

            // PRE-FIX REGRESSION GUARD: the 7-arg overload was the bug. If a
            // future refactor accidentally calls it here, this test fails.
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("mcp:leaf"), any(), any(), any(),
                eq(0), eq(7));
        }

        @Test
        @DisplayName("checkAndSkipMergeIfNoSuccessfulPredecessor (site #2): 8-arg overload called for merge node, 7-arg form NEVER reached")
        void checkAndSkipMergeUsesTriggerIdAwareOverload() {
            // Two predecessors → merge. Both SKIPPED → merge skips via convergence.
            // Site #2 inside checkAndSkipMergeIfNoSuccessfulPredecessor at line 406-411.
            BaseNode predA = mock(BaseNode.class);
            when(predA.getNodeId()).thenReturn("mcp:pred_a");
            when(predA.isMergeNode()).thenReturn(false);
            when(predA.isImplicitMerge()).thenReturn(false);

            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:merge_x");
            when(mergeNode.isMergeNode()).thenReturn(false);
            when(mergeNode.isImplicitMerge()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:pred_a", "mcp:pred_b"));

            BaseNode source = mock(BaseNode.class);
            when(source.getNodeId()).thenReturn("mcp:src");
            when(source.getSuccessors()).thenReturn(List.of(predA));
            when(predA.getSuccessors()).thenReturn(List.of(mergeNode));

            // Both predecessors resolved as SKIPPED, none COMPLETED → merge gets the skip.
            when(execution.getStepResult("mcp:pred_a")).thenReturn(
                StepExecutionResult.skipped("mcp:pred_a", "branch unselected"));
            when(execution.getStepResult("mcp:pred_b")).thenReturn(
                StepExecutionResult.skipped("mcp:pred_b", "branch unselected"));

            skipPropagationService.persistAndPropagateSkip(
                execution, source, "mcp:src", 0, 9, "trigger:cron");

            // Site #2 - merge node lands under the real trigger.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:merge_x"), any(), any(), any(),
                eq(0), eq(9), eq("trigger:cron"));

            // Negative guard - the 7-arg overload (the bug) must NEVER be called for the merge.
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:merge_x"), any(), any(), any(),
                eq(0), eq(9));
        }

        @Test
        @DisplayName("markEdgeToMergeSkipped (site #3): 8-arg overload called when ALL predecessors are FAILED/SKIPPED, 7-arg form NEVER reached")
        void markEdgeToMergeSkippedUsesTriggerIdAwareOverload() {
            // Direct call to markEdgeToMergeSkipped (engine-path) - site #3 at line 505-510.
            // Two predecessors, one already FAILED (in stepResult), engine reports the
            // second one as SKIPPED via this method.
            BaseNode mergeNode = mock(BaseNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:join_y");
            when(mergeNode.isMergeNode()).thenReturn(false);
            when(mergeNode.isImplicitMerge()).thenReturn(true);
            when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:failed", "mcp:skipped"));
            when(mergeNode.getSuccessors()).thenReturn(List.of());

            when(execution.getStepResult("mcp:failed")).thenReturn(
                StepExecutionResult.failure("mcp:failed", "boom", new RuntimeException("boom"), 500));
            when(execution.getStepResult("mcp:skipped")).thenReturn(
                StepExecutionResult.skipped("mcp:skipped", "branch unselected"));

            skipPropagationService.markEdgeToMergeSkipped(
                execution, mergeNode, "mcp:skipped", 0, 9, "trigger:cron");

            // Site #3 - merge convergence skip lands under the real trigger.
            verify(stepCompletionOrchestrator).completeSkippedStep(
                eq(execution), eq("core:join_y"), any(), any(), any(),
                eq(0), eq(9), eq("trigger:cron"));

            // Negative guard for site #3.
            verify(stepCompletionOrchestrator, never()).completeSkippedStep(
                eq(execution), eq("core:join_y"), any(), any(), any(),
                eq(0), eq(9));
        }
    }
}
