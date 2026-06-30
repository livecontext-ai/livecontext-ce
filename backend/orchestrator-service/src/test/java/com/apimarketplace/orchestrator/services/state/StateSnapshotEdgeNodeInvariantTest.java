package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public-API contract test for {@link StateSnapshot}: when the snapshot's public
 * mutation API is used correctly (one {@code incrementEdge(from, to, SKIPPED)}
 * call paired with one {@code markNodeSkipped(to)} call per traversal), the
 * resulting counters satisfy the linear-edge invariant
 *
 * <pre>
 *   edge.completed + edge.skipped  ==  node.completed + node.skipped + node.failed
 * </pre>
 *
 * <p>This is the structural contract that the prod bug
 * ({@code 656a4aed-47b6-4a9f-bfa7-9bff2f707cca}, Gmail Auto-Labeler epoch 2)
 * violated by writing 16 SKIPPED edge events while the target node accumulated
 * only 14 - the divergence symptom that surfaced the
 * {@code ReadyNodeCalculator.processCompletedNode} SKIPPED-envelope misclassification.
 * The runtime regression for that shape is pinned by
 * {@code ReadyNodeCalculatorSkippedEnvelopeTest} and
 * {@code SplitBranchSkippedPropagationTest}; this file pins the cheaper
 * API-level contract so any future refactor that breaks the 1:1 edge↔node
 * accounting fails fast.
 */
@DisplayName("StateSnapshot API contract - paired incrementEdge + markNodeSkipped preserves linear edge↔node 1:1")
class StateSnapshotEdgeNodeInvariantTest {

    private static final String EDGE_FROM = "mcp:apply_tech";
    private static final String EDGE_TO = "table:record_tech";
    private static final int EXPECTED_EVENT_COUNT = 16;

    @Test
    @DisplayName("Linear edge: N SKIPPED events on the edge imply N SKIPPED events on the target node")
    void linearEdgeEventCountMatchesTargetNodeEventCount() {
        // Arrange - replay the prod sequence: 16 items routed away from the tech
        // branch, each one marking the edge SKIPPED and the target node SKIPPED.
        // In a correct execution this is the ONLY way the counters can move on a
        // linear edge, so the post-condition is edge total == node total.
        //
        // The prod bug (run 656a4aed) violated this because ReadyNodeCalculator
        // re-traversed SKIPPED apply_tech as if it were COMPLETED, scheduling
        // record_tech to run and producing a phantom +1 on completed without
        // touching the edge counter. The runtime regression for that shape is
        // pinned by ReadyNodeCalculatorSkippedEnvelopeTest and
        // SplitBranchSkippedPropagationTest. This test pins the structural
        // invariant the snapshot itself exposes: under a correct usage of the
        // public API, edge total == node total.
        StateSnapshot snapshot = StateSnapshot.empty();
        for (int i = 0; i < EXPECTED_EVENT_COUNT; i++) {
            snapshot = snapshot.incrementEdge(EDGE_FROM, EDGE_TO, "SKIPPED")
                               .markNodeSkipped(EDGE_TO);
        }

        // Act
        StateSnapshot.EdgeCounts edgeCounts = snapshot.getEdgeCounts(EDGE_FROM, EDGE_TO);
        StateSnapshot.NodeCounts nodeCounts = snapshot.getNodeCounts(EDGE_TO);

        int edgeTotal = edgeCounts.completed() + edgeCounts.skipped();
        int nodeTotal = nodeCounts.completed() + nodeCounts.skipped() + nodeCounts.failed();

        // Assert - the invariant the prod bug violated.
        assertThat(nodeTotal)
                .as("Linear edge invariant violated on %s -> %s. "
                                + "Edge recorded %d events (completed=%d, skipped=%d), "
                                + "but node %s recorded %d events (completed=%d, skipped=%d, failed=%d). "
                                + "On a linear edge every traversal MUST mark the target node exactly once. "
                                + "Divergence indicates ReadyNodeCalculator double-traversed a SKIPPED "
                                + "node (prod 656a4aed-47b6-4a9f-bfa7-9bff2f707cca) - see "
                                + "ReadyNodeCalculator.processCompletedNode lines 312-348.",
                        EDGE_FROM, EDGE_TO,
                        edgeTotal, edgeCounts.completed(), edgeCounts.skipped(),
                        EDGE_TO, nodeTotal, nodeCounts.completed(), nodeCounts.skipped(),
                        nodeCounts.failed())
                .isEqualTo(edgeTotal);
        assertThat(edgeTotal)
                .as("Edge total event count must equal the expected %d (16 items routed away from this branch)",
                        EXPECTED_EVENT_COUNT)
                .isEqualTo(EXPECTED_EVENT_COUNT);
    }
}
