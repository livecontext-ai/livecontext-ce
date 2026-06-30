package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end regression test for the prod-incident double-skip bug
 * (run_<id>, workflow f084a6f5-281c-4581-8d87-58428a9b4bc1).
 *
 * <p>Wires the REAL {@link EdgeStatusEmitter}, REAL {@link EdgeStatusService} and REAL
 * {@link V2SkipPropagationService} together against a fake {@link StateSnapshotService}
 * that tallies every increment hitting the persistent {@code state_snapshot.edges} JSONB.
 * This mirrors the exact production sequence: a fork branch fails →
 * {@code V2ExecutionEventService.emitNodeComplete} drives {@code emitOutgoingEdges} which
 * batches a SKIPPED into {@code recordEdgeStatusesBatch}, then the engine invokes
 * {@code cascadeFailureToSuccessors}.
 *
 * <p>Pre-fix this test would have observed {@code skipped == 2}: one from the emitter's
 * batched flush, one from {@code markEdgeToMergeSkipped}'s immediate write. Post-fix the
 * cascade no longer touches the direct edge → the only writer is the emitter → exactly 1.
 *
 * <p>The test exists because pure mock-based unit tests on either service in isolation can
 * be silently invalidated by future refactors that move the marking responsibility around
 * (e.g. removing the {@code result.isFailure()} branch in {@code EdgeStatusEmitter:267-282}
 * would leave the edge at 0 - which a {@code verify(...).never()} assertion in the cascade
 * unit test would fail to catch). This integration test pins the contract end-to-end.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Edge skip double-increment integration regression")
class EdgeSkipDoubleIncrementIntegrationTest {

    /**
     * Fake StateSnapshotService that records every edge-status write into an in-memory
     * counter. Mirrors the additive semantics of {@code StateSnapshot.EdgeCounts.increment}
     * (non-idempotent), so a double-write surfaces as count=2.
     */
    private static class CountingStateSnapshotService extends StateSnapshotService {
        // edgeKey ("from->to") + status -> count
        final Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();

        CountingStateSnapshotService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void recordEdgeStatus(String runId, String from, String to, String status) {
            String key = from + "->" + to + ":" + status;
            tally.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public void recordEdgeStatusesBatch(String runId, Map<String, Map.Entry<String, Integer>> increments) {
            if (increments == null) return;
            for (var entry : increments.entrySet()) {
                String edgeKey = entry.getKey();
                // strip the "::SKIPPED" disambiguator that flushEdgeBatch adds when a
                // single edge has both COMPLETED and SKIPPED
                int sep = edgeKey.indexOf("::");
                if (sep > 0) edgeKey = edgeKey.substring(0, sep);
                String status = entry.getValue().getKey();
                int count = entry.getValue().getValue();
                String key = edgeKey + ":" + status;
                tally.computeIfAbsent(key, k -> new AtomicInteger()).addAndGet(count);
            }
        }

        int countOf(String from, String to, String status) {
            AtomicInteger v = tally.get(from + "->" + to + ":" + status);
            return v == null ? 0 : v.get();
        }
    }

    @Test
    @DisplayName("Regression run_<id>: real emitter + cascade pipeline writes failed→merge edge SKIPPED exactly once to StateSnapshot")
    void prodIncident_endToEnd_failedBranchToMergeEdgeSkippedExactlyOnceInStateSnapshot() {
        // ─────── Arrange: real EdgeStatusEmitter + EdgeStatusService + V2SkipPropagationService
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        CountingStateSnapshotService stateSnapshot = new CountingStateSnapshotService();
        EdgeStatusService edgeStatusService = new EdgeStatusService(eventPublisher, stateSnapshot);
        StepCompletionOrchestrator stepCompletion = mock(StepCompletionOrchestrator.class);
        V2SkipPropagationService skipPropagation = new V2SkipPropagationService(
            stepCompletion, edgeStatusService, new SimpleMeterRegistry());
        EdgeStatusEmitter emitter = new EdgeStatusEmitter(edgeStatusService, skipPropagation);

        // Plan: Fork(generate_both) → [openai_image OK, gemini_image FAILED] → Merge(collect_results)
        BaseNode merge = mock(BaseNode.class);
        when(merge.getNodeId()).thenReturn("core:collect_results");
        when(merge.isMergeNode()).thenReturn(true);
        when(merge.isImplicitMerge()).thenReturn(false);
        when(merge.getPredecessorIds()).thenReturn(List.of("mcp:openai_image", "mcp:gemini_image"));
        when(merge.getSuccessors()).thenReturn(List.of());

        BaseNode geminiFailed = mock(BaseNode.class);
        when(geminiFailed.getNodeId()).thenReturn("mcp:gemini_image");
        when(geminiFailed.getType()).thenReturn(NodeType.MCP);
        when(geminiFailed.getSuccessors()).thenReturn(List.of(merge));
        when(geminiFailed.getNextNodes(any())).thenReturn(List.of()); // BaseNode behaviour on failure
        when(geminiFailed.isBranchingNode()).thenReturn(false);
        when(geminiFailed.isExitNode()).thenReturn(false);
        when(geminiFailed.isStopOnErrorNode()).thenReturn(false);
        when(geminiFailed.isEndNode()).thenReturn(false);
        when(geminiFailed.isSplitNode()).thenReturn(false);

        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn("run_<id>");
        // Sibling branch is COMPLETED → merge will execute, not be pre-skipped by convergence
        when(execution.getStepResult("mcp:openai_image")).thenReturn(
            StepExecutionResult.success("mcp:openai_image", new HashMap<>(), 15951L));
        when(execution.getStepResult("mcp:gemini_image")).thenReturn(
            StepExecutionResult.failure("mcp:gemini_image", "Invalid value at 'generation_config'",
                new RuntimeException("Google 400"), 126L));

        NodeExecutionResult failResult = NodeExecutionResult.failure(
            "mcp:gemini_image", "Invalid value at 'generation_config'");

        // ─────── Act: replay the exact engine sequence (V2ExecutionEventService.emitNodeComplete
        //               wraps emitOutgoingEdges in beginEdgeBatch/flushEdgeBatch → the batched
        //               write hits recordEdgeStatusesBatch; then UnifiedExecutionEngine:534
        //               invokes cascadeFailureToSuccessors which used to add a 2nd write via
        //               markEdgeToMergeSkipped → recordEdgeStatus immediate)
        edgeStatusService.beginEdgeBatch();
        try {
            emitter.emitOutgoingEdges(execution, geminiFailed, /*itemIndex=*/0, /*iteration=*/null,
                failResult, /*suppressSkipPropagation=*/false, /*epoch=*/1, "trigger:image_prompt");
        } finally {
            edgeStatusService.flushEdgeBatch(execution.getRunId());
        }
        skipPropagation.cascadeFailureToSuccessors(
            execution, geminiFailed, 0, 1, "trigger:image_prompt", false,
            V2SkipPropagationService.SOURCE_SYNC);

        // ─────── Assert: exactly ONE SKIPPED on the failed→merge edge in StateSnapshot.
        // Pre-fix this would have been 2 (one batched flush + one immediate write). Post-fix
        // only the emitter writes; cascade does the convergence check without touching the edge.
        int skipped = stateSnapshot.countOf("mcp:gemini_image", "core:collect_results", "SKIPPED");
        assertEquals(1, skipped,
            "Failed→merge edge must be recorded SKIPPED exactly once across the emitter+cascade " +
            "pipeline (prod incident run_<id> produced skipped=2). Tally: " +
            stateSnapshot.tally);
        // No COMPLETED for that edge (the source failed)
        assertEquals(0, stateSnapshot.countOf("mcp:gemini_image", "core:collect_results", "COMPLETED"));
    }

    @Test
    @DisplayName("Split guardrail FAILED: emitter direct edges + per-item cascade write every SKIPPED edge exactly once")
    void splitGuardrailFailure_emitterAndPerItemCascadeWriteEachSkippedEdgeExactlyOnce() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        CountingStateSnapshotService stateSnapshot = new CountingStateSnapshotService();
        EdgeStatusService edgeStatusService = new EdgeStatusService(eventPublisher, stateSnapshot);
        StepCompletionOrchestrator stepCompletion = mock(StepCompletionOrchestrator.class);
        V2SkipPropagationService skipPropagation = new V2SkipPropagationService(
            stepCompletion, edgeStatusService, new SimpleMeterRegistry());
        EdgeStatusEmitter emitter = new EdgeStatusEmitter(edgeStatusService, skipPropagation);

        BaseNode merge = mock(BaseNode.class);
        when(merge.getNodeId()).thenReturn("core:verdict_gate");
        when(merge.isMergeNode()).thenReturn(true);
        when(merge.isImplicitMerge()).thenReturn(false);
        when(merge.getPredecessorIds()).thenReturn(List.of("core:cleared_note", "core:flagged_note"));
        when(merge.getSuccessors()).thenReturn(List.of());
        when(merge.getAllChildNodes()).thenReturn(List.of());

        BaseNode clearedNote = mock(BaseNode.class);
        when(clearedNote.getNodeId()).thenReturn("core:cleared_note");
        when(clearedNote.getSuccessors()).thenReturn(List.of(merge));
        when(clearedNote.getAllChildNodes()).thenReturn(List.of());

        BaseNode flaggedNote = mock(BaseNode.class);
        when(flaggedNote.getNodeId()).thenReturn("core:flagged_note");
        when(flaggedNote.getSuccessors()).thenReturn(List.of(merge));
        when(flaggedNote.getAllChildNodes()).thenReturn(List.of());

        ExecutionNode guardrail = mock(ExecutionNode.class);
        when(guardrail.getNodeId()).thenReturn("agent:compliance_screen");
        when(guardrail.getType()).thenReturn(NodeType.AGENT);
        when(guardrail.isBranchingNode()).thenReturn(true);
        when(guardrail.getSuccessors()).thenReturn(List.of());
        when(guardrail.getAllChildNodes()).thenReturn(List.of(clearedNote, flaggedNote));
        when(guardrail.getNextNodes(any())).thenReturn(List.of());
        when(guardrail.getSkippedChildNodes(any())).thenReturn(List.of(clearedNote, flaggedNote));
        when(guardrail.getBranchTargetsByPort()).thenReturn(Map.of(
            "pass", List.of(clearedNote),
            "fail", List.of(flaggedNote)));
        when(guardrail.getSelectedPort(any())).thenReturn(null);
        when(guardrail.shouldPropagateSkipOnBranching()).thenReturn(true);

        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn("run_split_guardrail_failure");

        NodeExecutionResult failResult = NodeExecutionResult.failure(
            "agent:compliance_screen", "Guardrail provider failed");

        edgeStatusService.beginEdgeBatch();
        try {
            emitter.emitOutgoingEdges(execution, guardrail, /*itemIndex=*/2, /*iteration=*/null,
                failResult, /*suppressSkipPropagation=*/true, /*epoch=*/4, "trigger:review", /*splitScope=*/true);
        } finally {
            edgeStatusService.flushEdgeBatch(execution.getRunId());
        }
        skipPropagation.cascadeFailureToSuccessors(
            execution, guardrail, 2, 4, "trigger:review", true, V2SkipPropagationService.SOURCE_ASYNC);

        assertEquals(1, stateSnapshot.countOf("agent:compliance_screen", "core:cleared_note", "SKIPPED"),
            "guardrail->cleared direct edge must be written once by the emitter");
        assertEquals(1, stateSnapshot.countOf("agent:compliance_screen", "core:flagged_note", "SKIPPED"),
            "guardrail->flagged direct edge must be written once by the emitter");
        assertEquals(1, stateSnapshot.countOf("core:cleared_note", "core:verdict_gate", "SKIPPED"),
            "cleared->merge descendant edge must be written once by the per-item cascade");
        assertEquals(1, stateSnapshot.countOf("core:flagged_note", "core:verdict_gate", "SKIPPED"),
            "flagged->merge descendant edge must be written once by the per-item cascade");
    }
}
