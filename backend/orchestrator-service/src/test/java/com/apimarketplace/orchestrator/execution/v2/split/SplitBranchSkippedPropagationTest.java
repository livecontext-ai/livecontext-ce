package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Higher-level regression test simulating the prod scenario where a classify
 * agent inside a split routes <em>all</em> items to non-tech categories:
 *
 * <pre>
 *   core:split ─▶ agent:classify ─▶ category_0..category_4: handled
 *                                ─▶ category_5: mcp:apply_tech ─▶ table:record_tech
 *                                ─▶ category_6 (urgent): mcp:apply_urgent ─▶ table:record_urgent
 * </pre>
 *
 * <p>Run {@code 656a4aed-47b6-4a9f-bfa7-9bff2f707cca}, epoch 2: all 3 items
 * picked category_6 (urgent), so {@code mcp:apply_tech} was correctly SKIPPED
 * for every item. The prod bug then erroneously caused
 * {@code table:record_tech} to be re-entered through the SKIPPED-with-envelope
 * path in {@code ReadyNodeCalculator.processCompletedNode}, leading to a "Tech &amp;
 * Dev" row being INSERTED for an email Gmail had actually labelled "Urgent".
 *
 * <p>This test reproduces the routing decision (all items to non-tech) and
 * asserts the engine-level outcome: {@code table:record_tech} must NOT become
 * ready, and the {@link StateSnapshot} accumulated across the 3 items must show
 * exactly 0 COMPLETED runs of {@code table:record_tech} (and 3 SKIPPED).
 *
 * <p>The test is written at the {@link ReadyNodeCalculator} level - the
 * smallest seam that exercises the SKIPPED-envelope misclassification while
 * still iterating over multiple split items. A full {@code SpringBootTest}
 * would add minutes of wiring with no extra coverage of the bug shape.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Split branch SKIPPED propagation - record_tech must never see a downstream insert when classify routes elsewhere")
class SplitBranchSkippedPropagationTest {

    private static final String RUN_ID = "run-656a4aed";
    private static final String TRIGGER_ID = "trigger:gmail_label";
    private static final int EPOCH = 2;
    private static final int ITEM_COUNT = 3;

    @Mock
    private MergeNodeAnalyzer mergeNodeAnalyzer;

    @Mock
    private ExecutionContext context;

    @Mock
    private ExecutionTree tree;

    @Mock
    private WorkflowPlan plan;

    @Mock
    private StateSnapshotService stateSnapshotService;

    private ReadyNodeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ReadyNodeCalculator(mergeNodeAnalyzer, stateSnapshotService, null);
        when(context.getAllStepOutputs()).thenReturn(Map.of());
        when(context.runId()).thenReturn(RUN_ID);
        when(context.triggerId()).thenReturn(TRIGGER_ID);
        when(context.epoch()).thenReturn(EPOCH);
    }

    @Test
    @DisplayName("3-item split, all routed to urgent: table:record_tech ends SKIPPED x3 / COMPLETED x0 - no Tech & Dev row leaks")
    void splitBranchSkippedDoesNotLeakToDownstreamTableInsert() {
        // Arrange - build the relevant slice of the prod graph. apply_tech is the
        // node we exercise; its successor record_tech is the row-insert we must
        // protect from leaking.
        ExecutionNode applyTech = mock(ExecutionNode.class);
        ExecutionNode recordTech = mock(ExecutionNode.class);

        when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");
        when(applyTech.getType()).thenReturn(NodeType.MCP);
        when(applyTech.getNextNodes(any())).thenReturn(List.of(recordTech));
        when(applyTech.getAllChildNodes()).thenReturn(List.of());

        when(recordTech.getNodeId()).thenReturn("table:record_tech");
        when(recordTech.getType()).thenReturn(NodeType.MCP);
        when(recordTech.canExecute(context)).thenReturn(true);
        when(recordTech.getPredecessorIds()).thenReturn(List.of("mcp:apply_tech"));

        when(tree.getRootNodes()).thenReturn(List.of(applyTech));
        when(tree.getPlan()).thenReturn(plan);
        when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:apply_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "table:record_tech")).thenReturn(false);

        // apply_tech is marked SKIPPED in ExecutionState - NodeStatus.SKIPPED.
        // isCompleted is overloaded as "any terminal state" (true for SKIPPED too); the
        // canonical SKIPPED signal the fix uses is isSkipped(nodeId).
        when(context.isCompleted("mcp:apply_tech")).thenReturn(true);
        when(context.isStarted("mcp:apply_tech")).thenReturn(true);
        when(context.isFailed("mcp:apply_tech")).thenReturn(false);
        when(context.isSkipped("mcp:apply_tech")).thenReturn(true);

        // …with the same SKIPPED status envelope shape the prod persistence path wrote.
        Map<String, Object> skippedEnvelope = Map.of(
                "output", Map.of(
                        "_status", "SKIPPED",
                        "_duration_ms", 0,
                        "_display_name", "livecontext"
                ),
                "statusValue", "skipped",
                "statusMessage", "No items routed to this branch",
                "graphNodeId", "mcp:apply_tech"
        );
        when(context.getStepOutput("mcp:apply_tech")).thenReturn(Optional.of(skippedEnvelope));

        // record_tech starts unexecuted for every item.
        when(context.isCompleted("table:record_tech")).thenReturn(false);
        when(context.isStarted("table:record_tech")).thenReturn(false);

        // Snapshot view that the calculator's isCompletedInSnapshot consults - apply_tech
        // is in this epoch's completed set (because the SKIPPED-completion records it
        // there), but record_tech is NOT. We start it empty and accumulate per item.
        StateSnapshot[] snapshotHolder = new StateSnapshot[]{
                StateSnapshot.empty().markNodeCompleted(TRIGGER_ID, "mcp:apply_tech", EPOCH)
        };
        when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenAnswer(inv -> snapshotHolder[0]);
        when(stateSnapshotService.getCompletedNodeIds(RUN_ID, TRIGGER_ID, EPOCH))
                .thenAnswer(inv -> snapshotHolder[0].getCompletedNodeIds(TRIGGER_ID, EPOCH));
        when(stateSnapshotService.getCompletedNodeIds(RUN_ID))
                .thenAnswer(inv -> snapshotHolder[0].getCompletedNodeIds());

        // Act - replay the split's 3-item loop. For each item the engine asks
        // ReadyNodeCalculator what comes next, and per the bug, record_tech would
        // get added to the ready set (and then executed by the engine, inserting a
        // row). We capture every ready set and accumulate the snapshot state the
        // engine would have written had it actually executed.
        boolean recordTechLeakedAnyItem = false;
        for (int itemIndex = 0; itemIndex < ITEM_COUNT; itemIndex++) {
            when(context.itemIndex()).thenReturn(itemIndex);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            if (readyNodes.contains("table:record_tech")) {
                // In the buggy code path this is hit. We simulate what the engine
                // would do next - execute the table insert and mark it COMPLETED -
                // so the final snapshot assertion below has the realistic count
                // shape that prod observed.
                recordTechLeakedAnyItem = true;
                snapshotHolder[0] = snapshotHolder[0]
                        .markNodeCompleted(TRIGGER_ID, "table:record_tech", EPOCH);
            } else {
                // Correct path: skip propagation marks the downstream SKIPPED.
                snapshotHolder[0] = snapshotHolder[0]
                        .markNodeSkipped(TRIGGER_ID, "table:record_tech", EPOCH);
            }
        }

        // Assert - record_tech must NOT have leaked into the ready set on ANY item,
        // and the final snapshot must reflect 0 COMPLETED / 3 SKIPPED.
        StateSnapshot.NodeCounts recordTechCounts =
                snapshotHolder[0].getNodeCounts("table:record_tech");

        assertThat(recordTechLeakedAnyItem)
                .as("table:record_tech leaked into the ready set on at least one of the "
                        + "%d split items - this is the prod data-corruption shape "
                        + "(run 656a4aed-47b6-4a9f-bfa7-9bff2f707cca: 'Tech & Dev' row "
                        + "inserted for an email Gmail labelled 'Urgent'). Root cause: "
                        + "ReadyNodeCalculator.processCompletedNode treating a SKIPPED "
                        + "node with a status envelope as COMPLETED.", ITEM_COUNT)
                .isFalse();

        assertThat(recordTechCounts.completed())
                .as("table:record_tech must have 0 COMPLETED runs when classify routes "
                        + "every item to a non-tech category. Got completed=%d, skipped=%d.",
                        recordTechCounts.completed(), recordTechCounts.skipped())
                .isZero();

        assertThat(recordTechCounts.skipped())
                .as("table:record_tech must have %d SKIPPED runs (one per split item). "
                        + "Got skipped=%d.", ITEM_COUNT, recordTechCounts.skipped())
                .isEqualTo(ITEM_COUNT);
    }
}
