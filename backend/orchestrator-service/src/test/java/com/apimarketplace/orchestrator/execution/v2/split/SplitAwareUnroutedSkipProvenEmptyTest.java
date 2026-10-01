package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Regression test for prod run {@code run_<id>} (workflow 1bffde93,
 * "no status behind a skipped agent").
 *
 * <p><b>Observed:</b> inside the {@code core:each_mail} split, {@code agent:draft_reply}
 * was persisted SKIPPED with reason {@code "No items routed to this branch"} in 22 of the
 * run's 29 epochs. In every one of those epochs its whole downstream chain
 * ({@code core:prep_draft} -> {@code core:approve_reply} -> {@code core:send_reply} /
 * {@code table:log_replied} / {@code table:log_reply_rejected}) had NO row at all: not
 * SKIPPED, not anything. The 6 epochs that did cascade are the ones where the merge
 * convergence path won the race and skipped the node with reason
 * {@code "All predecessors failed or skipped"} instead.
 *
 * <p><b>Why nothing ran:</b> {@link SplitAwareNodeExecutor} returns a bare SKIPPED for an
 * empty routing set, without {@link ExecutionMetadataKeys#CASCADE_SKIP_TO_SUCCESSORS}, so
 * {@code UnifiedExecutionEngine.shouldCascadeSkipFromResult} declines to cascade. In
 * step-by-step mode (which is how this run executes) nothing else picks the successors up
 * either: {@code ReadyNodeCalculator} does not consider a node whose predecessor is SKIPPED
 * ready, so the branch simply dies. Prod log, epoch 29:
 * {@code [V2StepByStep] Node agent:draft_reply completed. Ready nodes: []}.
 *
 * <p><b>Why the bare skip exists</b> (hotfix {@code 11c31a12b}): an empty routing set is
 * ambiguous. It also occurs when a predecessor's {@code workflow_step_data} writes fail, and
 * cascading from that state mass-marks healthy descendants SKIPPED (counter inflation, prod
 * run {@code 656a4aed}). The fix under test does not undo that: it distinguishes the two by
 * asking whether each predecessor left TERMINAL rows for the epoch, and cascades only when
 * the emptiness is provable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitAwareNodeExecutor - unrouted skip cascades only when the emptiness is proven")
class SplitAwareUnroutedSkipProvenEmptyTest {

    private static final String RUN_ID = "run_<id>";
    private static final int EPOCH = 29;
    private static final String SPLIT_NODE_ID = "core:each_mail:0";

    @Mock private SplitContextManager contextManager;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private ExecutionNode draftReply;

    private SplitAwareNodeExecutor executor;
    private Method isUnroutedEmptinessProven;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        executor = new SplitAwareNodeExecutor(
            contextManager, null, null, null, stepDataRepository, null,
            Executors.newFixedThreadPool(2));

        // Exact prod shape: 3 plain (non port-qualified) incoming edges, so the node is an
        // implicit merge and is NOT an explicit choice-branch target. The category_N choice
        // happens one hop upstream, on agent:classify_mail -> core:move_*.
        when(draftReply.getNodeId()).thenReturn("agent:draft_reply");
        when(draftReply.getPredecessorIds()).thenReturn(
            List.of("core:move_clients", "core:move_prospects", "core:move_partenaires"));

        isUnroutedEmptinessProven = SplitAwareNodeExecutor.class.getDeclaredMethod(
            "isUnroutedEmptinessProven", ExecutionNode.class, String.class, int.class, String.class);
        isUnroutedEmptinessProven.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("Every predecessor left terminal rows: the empty routing is proven, so the skip must cascade")
    void provenWhenAllPredecessorsHaveTerminalRows() throws Exception {
        // Prod epoch 2 verified in DB: the 3 predecessors hold 36 SKIPPED rows between them.
        // They ran, they routed nothing here. Nothing is missing.
        eachPredecessorHasTerminalRows(List.of(0, 1, 2, 3, 4, 5));

        assertTrue(proven(), "3 predecessors with terminal rows must count as a proven empty routing");
    }

    @Test
    @DisplayName("A predecessor with no terminal row: emptiness unproven, cascade withheld (hotfix 11c31a12b protection)")
    void notProvenWhenAPredecessorHasNoTerminalRow() throws Exception {
        // The run 656a4aed shape: get_content's writes aborted, so it has no row for the
        // epoch. Cascading here would fan a persistence failure across healthy descendants.
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_clients", EPOCH))
            .thenReturn(List.of(0, 1));
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_prospects", EPOCH))
            .thenReturn(List.of());
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_partenaires", EPOCH))
            .thenReturn(List.of(0, 1));

        assertFalse(proven(), "a predecessor with zero terminal rows must withhold the cascade");
    }

    @Test
    @DisplayName("Query failure: emptiness unproven, cascade withheld (fail closed)")
    void notProvenWhenLookupThrows() throws Exception {
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_clients", EPOCH))
            .thenThrow(new RuntimeException("connection reset"));

        assertFalse(proven(), "a failed provability lookup must not be read as proof");
    }

    @Test
    @DisplayName("No predecessor to attest anything: cascade withheld")
    void notProvenWithoutPredecessors() throws Exception {
        when(draftReply.getPredecessorIds()).thenReturn(List.of());

        assertFalse(proven(), "a node with no predecessor cannot prove an empty routing");
    }

    @Test
    @DisplayName("The split node itself is not counted as an attesting predecessor")
    void splitNodeIsNotAnAttestingPredecessor() throws Exception {
        // A direct split successor's only predecessor is the split node, which is the source
        // of items rather than a routing filter (same exclusion as the routing lookup). It
        // must not be able to certify an empty routing on its own.
        when(draftReply.getPredecessorIds()).thenReturn(List.of("core:each_mail"));

        assertFalse(proven(), "the split node must not certify the emptiness by itself");
    }

    @Test
    @DisplayName("A proven-empty SKIPPED result drives the engine cascade; an unproven one does not")
    void engineCascadesOnlyOnTheProvenResult() throws Exception {
        // Second half of the chain: the metadata flag is what UnifiedExecutionEngine reads to
        // decide whether to call cascadeFailureToSuccessors, on BOTH the auto and the
        // step-by-step call sites. Pin it here so a drift in either half is caught.
        Map<String, Object> output = Map.of(
            "skip_reason", "No items routed to this branch",
            ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, Boolean.TRUE);

        NodeExecutionResult unproven = new NodeExecutionResult(
            "agent:draft_reply", NodeStatus.SKIPPED, output,
            Optional.of("No items routed to this branch"), output, 0);
        assertFalse(engineWouldCascade(unproven),
            "an unproven unrouted skip must stay bare - this is the hotfix behavior");

        Map<String, Object> provenMetadata = Map.of(
            "skip_reason", "No items routed to this branch",
            ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, Boolean.TRUE,
            ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, Boolean.TRUE);
        NodeExecutionResult provenResult = new NodeExecutionResult(
            "agent:draft_reply", NodeStatus.SKIPPED, output,
            Optional.of("No items routed to this branch"), provenMetadata, 0);
        assertTrue(engineWouldCascade(provenResult),
            "a proven unrouted skip must reach cascadeFailureToSuccessors");

        // The flag rides in metadata, not in the result's own output. (It still reaches the
        // persisted output through NodeCompletionService's metadata merge, where
        // StepCompletionOrchestrator.stripInternalCompletionMetadata removes it.)
        assertEquals(2, provenResult.output().size(), "the cascade flag must not be part of the node output");
        assertFalse(provenResult.output().containsKey(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS));
    }

    @Test
    @DisplayName("End to end: the unrouted SKIPPED result itself carries the cascade flag when proven, and stays bare when not")
    void unroutedSkipResultCarriesTheFlagOnlyWhenProven() throws Exception {
        // The actual regression: pre-fix this call returned a SKIPPED whose metadata never
        // contained CASCADE_SKIP_TO_SUCCESSORS, whatever the upstream state, so prep_draft and
        // everything behind it were left with no status.
        when(draftReply.isImplicitMerge()).thenReturn(true);
        // Routing lookup: no predecessor COMPLETED any item -> empty routing (the prod state).
        noPredecessorCompletedAnyItem();

        eachPredecessorHasTerminalRows(List.of(0, 1, 2, 3, 4, 5));
        NodeExecutionResult proven = runUnroutedBranch();
        assertEquals(NodeStatus.SKIPPED, proven.status());
        assertEquals("No items routed to this branch", proven.output().get("skip_reason"));
        assertTrue(Boolean.TRUE.equals(proven.metadata().get(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS)),
            "a provable empty routing must hand the engine the cascade flag");

        // Same call, but one predecessor left no trace: the flag must not be set.
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_prospects", EPOCH))
            .thenReturn(List.of());
        NodeExecutionResult unproven = runUnroutedBranch();
        assertEquals(NodeStatus.SKIPPED, unproven.status());
        assertFalse(unproven.metadata().containsKey(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS),
            "an unprovable empty routing must stay bare - hotfix 11c31a12b protection");
    }

    // ===== Helpers =====

    /** Drives the private unrouted-skip branch of {@code executeForAllItemsAndTraverse}. */
    private NodeExecutionResult runUnroutedBranch() throws Exception {
        Method m = SplitAwareNodeExecutor.class.getDeclaredMethod(
            "executeForAllItemsAndTraverse",
            ExecutionNode.class,
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext.class,
            SplitContext.class,
            String.class,
            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class,
            com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem.class,
            int.class,
            SplitAwareNodeExecutor.SuccessorTraverser.class,
            SplitExecutionOptions.class,
            Map.class);
        m.setAccessible(true);

        com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context =
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext.create(
                RUN_ID, "workflow-run-1", "tenant-1", "item-0", 0, Map.of(),
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))
            .withEpoch(EPOCH);

        // 6 mails in the split, exactly the prod epoch-29 shape.
        SplitContext splitContext = SplitContext.create(
            SPLIT_NODE_ID, List.of("m0", "m1", "m2", "m3", "m4", "m5"));

        return (NodeExecutionResult) m.invoke(executor,
            draftReply, context, splitContext, RUN_ID,
            null, null, 0, null, null, Map.of("agent:draft_reply", draftReply));
    }

    private void noPredecessorCompletedAnyItem() {
        when(stepDataRepository.findPassedItemIndicesByEpoch(
            org.mockito.ArgumentMatchers.eq(RUN_ID), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(EPOCH))).thenReturn(List.of());
    }

    private void eachPredecessorHasTerminalRows(List<Integer> indices) {
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_clients", EPOCH))
            .thenReturn(indices);
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_prospects", EPOCH))
            .thenReturn(indices);
        when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "core:move_partenaires", EPOCH))
            .thenReturn(indices);
    }

    /**
     * {@code shouldCascadeSkipFromResult} is package-private in the engine package (kept that way
     * on purpose - it is the engine's contract with handler nodes). Reached by reflection rather
     * than widening its visibility just for this cross-package assertion.
     */
    private boolean engineWouldCascade(NodeExecutionResult result) throws Exception {
        Method m = UnifiedExecutionEngine.class.getDeclaredMethod(
            "shouldCascadeSkipFromResult", NodeExecutionResult.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, result);
    }

    private boolean proven() throws Exception {
        return (boolean) isUnroutedEmptinessProven.invoke(
            executor, draftReply, RUN_ID, EPOCH, SPLIT_NODE_ID);
    }
}
