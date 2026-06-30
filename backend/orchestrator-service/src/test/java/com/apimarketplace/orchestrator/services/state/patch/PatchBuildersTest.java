package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the 3 patch builders. Verifies:
 * <ul>
 *   <li>shape of emitted patches (paths + JSON values)</li>
 *   <li>P2.3 elide compatibility (omits runningNodeIds when flag ON)</li>
 *   <li>NO_OP detection per-mutator semantics</li>
 *   <li>FALLBACK when path prerequisites are missing</li>
 * </ul>
 *
 * <p>Snapshots are built via the public mutator API (no test-only constructor),
 * so tests stay aligned with production semantics.
 */
class PatchBuildersTest {

    private static final String TRIGGER = "trigger:webhook";
    private static final int EPOCH = 5;
    private static final String NODE = "n1";
    private static final String TENANT = "tenant-a";

    private ObjectMapper mapper;
    private TenantElideFlagResolver elideOn;
    private TenantElideFlagResolver elideOff;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        elideOn = tenantId -> true;
        elideOff = tenantId -> false;
    }

    /** Snapshot with the epoch initialized and {@code n1} in running set. */
    private StateSnapshot withNodeRunning() {
        return StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
    }

    // ==================================================================
    // MarkNodeCompletedPatchBuilder
    // ==================================================================

    @Nested
    @DisplayName("MarkNodeCompletedPatchBuilder")
    class CompletedBuilder {

        private MarkNodeCompletedPatchBuilder builderElideOff() {
            return new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        }

        private MarkNodeCompletedPatchBuilder builderElideOn() {
            return new MarkNodeCompletedPatchBuilder(mapper, elideOn);
        }

        @Test
        @DisplayName("Returns FALLBACK when before lacks the epoch (path-init prerequisite)")
        void fallbackWhenEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(empty, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("Emits seq + 3 sets + nodeCounts when elide=OFF")
        void emitsFivePatchesElideOff() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(5);
            // First patch is seq with the bumped value
            assertThat(patches.get(0).path()).containsExactly("seq");
            assertThat(patches.get(0).jsonValue()).isEqualTo(Long.toString(after.getSeq()));
            // Verify all expected leaf names are present
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.COMPLETED_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.READY_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
            // NodeCounts replaces the full record at nodes.{n1}
            assertThat(patches).anyMatch(p ->
                    p.path().length == 2 && p.path()[0].equals("nodes") && p.path()[1].equals(NODE));
        }

        @Test
        @DisplayName("Omits runningNodeIds patch when elide=ON (P2.3 compat)")
        void omitsRunningNodeIdsWhenElideOn() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOn()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            assertThat(patches).noneMatch(p ->
                    p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }

        @Test
        @DisplayName("Never returns NO_OP - markNodeCompleted always bumps NodeCounts")
        void neverNoOpEvenForAlreadyCompleted() {
            // Mark twice: NodeCounts.completed bumps from 1 to 2 - must produce a patch.
            StateSnapshot before = withNodeRunning().markNodeCompleted(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isNoOp()).isFalse();
            assertThat(result.isPatch()).isTrue();
        }

        @Test
        @DisplayName("Path with colon in triggerId round-trips (`trigger:webhook` is canonical)")
        void colonInTriggerIdRoundTrips() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isPatch()).isTrue();
            // Find the completedNodeIds patch and verify its path-array literal
            var completedPatch = result.patches().orElseThrow().stream()
                    .filter(p -> p.path()[p.path().length - 1].equals(PatchPaths.COMPLETED_NODE_IDS))
                    .findFirst()
                    .orElseThrow();
            String literal = completedPatch.toPostgresArrayLiteral();
            assertThat(literal).contains("\"trigger:webhook\"");
            assertThat(literal).contains("\"epochs\",\"" + EPOCH + "\"");
        }
    }

    // ==================================================================
    // MarkNodeAwaitingSignalPatchBuilder
    // ==================================================================

    @Nested
    @DisplayName("MarkNodeAwaitingSignalPatchBuilder")
    class AwaitingBuilder {

        private MarkNodeAwaitingSignalPatchBuilder builderElideOff() {
            return new MarkNodeAwaitingSignalPatchBuilder(mapper, elideOff);
        }

        @Test
        @DisplayName("Returns NO_OP when nodeId is already in awaitingSignalNodeIds")
        void noOpWhenAlreadyAwaiting() {
            // First mark awaiting, then mark again - second call must be NO_OP.
            StateSnapshot before = withNodeRunning().markNodeAwaitingSignal(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits 4 patches when elide=OFF and node not yet awaiting")
        void emitsFourPatchesElideOff() {
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.AWAITING_SIGNAL_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.READY_NODE_IDS));
        }

        @Test
        @DisplayName("Omits runningNodeIds patch when elide=ON")
        void omitsRunningWhenElideOn() {
            MarkNodeAwaitingSignalPatchBuilder builder = new MarkNodeAwaitingSignalPatchBuilder(
                    mapper, elideOn);
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builder
                    .build(before, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(3);
            assertThat(patches).noneMatch(p ->
                    p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }

        @Test
        @DisplayName("Returns FALLBACK when before lacks the epoch")
        void fallbackWhenEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.markNodeAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builderElideOff()
                    .build(empty, after, TRIGGER, EPOCH, NODE, TENANT);

            assertThat(result.isFallback()).isTrue();
        }
    }

    // ==================================================================
    // IncrementNodeCountsOnlyPatchBuilder
    // ==================================================================

    @Nested
    @DisplayName("IncrementNodeCountsOnlyPatchBuilder")
    class IncrementBuilder {

        private IncrementNodeCountsOnlyPatchBuilder builder() {
            return new IncrementNodeCountsOnlyPatchBuilder(mapper);
        }

        @Test
        @DisplayName("Returns NO_OP when before.equals(after) (count<=0 path)")
        void noOpWhenSameCounts() {
            // The Java mutator returns `this` when count <= 0. Here, simulate with count=0 input
            // - the builder receives the SAME snapshot for before and after.
            StateSnapshot before = StateSnapshot.empty()
                    .incrementNodeCountsOnly(NODE, "COMPLETED", 3);
            StateSnapshot after = before; // no change

            JsonbPatchBuilder.Result result = builder().build(before, after, NODE);

            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits seq + nodes patch on real increment")
        void emitsTwoPatchesOnIncrement() {
            StateSnapshot before = StateSnapshot.empty()
                    .incrementNodeCountsOnly(NODE, "COMPLETED", 3);
            StateSnapshot after = before.incrementNodeCountsOnly(NODE, "COMPLETED", 2).withIncrementedSeq();

            JsonbPatchBuilder.Result result = builder().build(before, after, NODE);

            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(2);
            assertThat(patches.get(0).path()).containsExactly("seq");
            assertThat(patches.get(1).path()).containsExactly("nodes", NODE);
        }

        @Test
        @DisplayName("Returns FALLBACK when before.nodes is empty (path-init prerequisite for first node-counts write)")
        void fallbackOnEmptyNodesMap() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.incrementNodeCountsOnly(NODE, "COMPLETED", 1).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, NODE);
            assertThat(result.isFallback()).isTrue();
        }
    }

    // ==================================================================
    // Round 2 builders: Failed / Skipped / EpochOnly×2 / PartialFailure / ResolveAwaitingSignal
    // ==================================================================

    @Nested
    @DisplayName("MarkNodeFailedPatchBuilder")
    class FailedBuilder {
        private MarkNodeFailedPatchBuilder builder() {
            return new MarkNodeFailedPatchBuilder(mapper, elideOff);
        }

        @Test
        @DisplayName("Emits seq + 3 sets + nodeCounts when elide=OFF")
        void emitsFivePatches() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeFailed(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            assertThat(result.patches().orElseThrow()).hasSize(5);
            assertThat(result.patches().get()).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.FAILED_NODE_IDS));
        }

        @Test
        @DisplayName("Never NO_OP - NodeCounts.failed bumps unconditionally")
        void neverNoOp() {
            StateSnapshot before = withNodeRunning().markNodeFailed(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeFailed(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
        }

        @Test
        @DisplayName("Returns FALLBACK when epoch missing")
        void fallbackEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.markNodeFailed(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("Omits runningNodeIds patch when elide=ON (P2.3 compat - symmetric with full-rewrite serializer)")
        void omitsRunningWhenElideOn() {
            MarkNodeFailedPatchBuilder b = new MarkNodeFailedPatchBuilder(mapper, elideOn);
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeFailed(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = b.build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            assertThat(patches).noneMatch(p ->
                    p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }
    }

    @Nested
    @DisplayName("MarkNodeSkippedPatchBuilder")
    class SkippedBuilder {
        private MarkNodeSkippedPatchBuilder builder() {
            return new MarkNodeSkippedPatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + 2 sets + nodeCounts (no runningNodeIds touch)")
        void emitsFourPatches() {
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeSkipped(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.SKIPPED_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.READY_NODE_IDS));
            // Skipped does NOT touch runningNodeIds
            assertThat(patches).noneMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }

        @Test
        @DisplayName("Never NO_OP - NodeCounts.skipped bumps even on duplicate (per StateSnapshot:588 increment)")
        void neverNoOp() {
            // Mark twice: even if nodeId already in skippedNodeIds, NodeCounts.skipped still bumps.
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH)
                    .markNodeSkipped(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeSkipped(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isNoOp()).isFalse();
            assertThat(result.isPatch()).isTrue();
        }
    }

    @Nested
    @DisplayName("MarkNodeCompletedEpochOnlyPatchBuilder")
    class EpochOnlyCompletedBuilder {
        private MarkNodeCompletedEpochOnlyPatchBuilder builder() {
            return new MarkNodeCompletedEpochOnlyPatchBuilder(mapper, elideOff);
        }

        @Test
        @DisplayName("NO_OP when nodeId already completed (set-pure idempotent)")
        void noOpAlreadyCompleted() {
            StateSnapshot before = withNodeRunning().markNodeCompletedEpochOnly(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeCompletedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits 4 patches (seq + 3 sets, NO nodeCounts) on first completion")
        void emitsFourSetPatches() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeCompletedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            // Critical: NO nodes path emitted (epoch-only does NOT bump NodeCounts)
            assertThat(patches).noneMatch(p ->
                    p.path().length == 2 && p.path()[0].equals("nodes"));
        }

        @Test
        @DisplayName("Omits runningNodeIds when elide=ON")
        void omitsRunningWhenElideOn() {
            MarkNodeCompletedEpochOnlyPatchBuilder b = new MarkNodeCompletedEpochOnlyPatchBuilder(mapper, elideOn);
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeCompletedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = b.build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(3);
            assertThat(patches).noneMatch(p ->
                    p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }
    }

    @Nested
    @DisplayName("MarkNodeFailedEpochOnlyPatchBuilder")
    class EpochOnlyFailedBuilder {
        private MarkNodeFailedEpochOnlyPatchBuilder builder() {
            return new MarkNodeFailedEpochOnlyPatchBuilder(mapper, elideOff);
        }

        @Test
        @DisplayName("NO_OP when nodeId already failed")
        void noOpAlreadyFailed() {
            StateSnapshot before = withNodeRunning().markNodeFailedEpochOnly(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodeFailedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits 4 patches (seq + 3 sets, NO nodeCounts)")
        void emitsFourSetPatches() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeFailedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            assertThat(result.patches().orElseThrow()).hasSize(4);
            assertThat(result.patches().get()).noneMatch(p -> p.path().length == 2 && p.path()[0].equals("nodes"));
        }

        @Test
        @DisplayName("Omits runningNodeIds when elide=ON")
        void omitsRunningWhenElideOn() {
            MarkNodeFailedEpochOnlyPatchBuilder b = new MarkNodeFailedEpochOnlyPatchBuilder(mapper, elideOn);
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodeFailedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = b.build(before, after, TRIGGER, EPOCH, NODE, TENANT);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(3);
            assertThat(patches).noneMatch(p ->
                    p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));
        }
    }

    @Nested
    @DisplayName("MarkNodePartialFailurePatchBuilder")
    class PartialFailureBuilder {
        private MarkNodePartialFailurePatchBuilder builder() {
            return new MarkNodePartialFailurePatchBuilder(mapper);
        }

        @Test
        @DisplayName("NO_OP when nodeId already in partialFailedNodeIds")
        void noOpIdempotent() {
            StateSnapshot before = withNodeRunning().markNodePartialFailure(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.markNodePartialFailure(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits 2 patches (seq + partialFailedNodeIds)")
        void emitsTwoPatches() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.markNodePartialFailure(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(2);
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.PARTIAL_FAILED_NODE_IDS));
        }
    }

    @Nested
    @DisplayName("ResolveAwaitingSignalPatchBuilder")
    class ResolveBuilder {
        private ResolveAwaitingSignalPatchBuilder builder() {
            return new ResolveAwaitingSignalPatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + completedNodeIds + awaitingSignalNodeIds + nodeCounts (4 patches)")
        void emitsFourPatches() {
            // Set up: node was awaiting, now resolving
            StateSnapshot before = withNodeRunning().markNodeAwaitingSignal(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.resolveAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(4);
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.COMPLETED_NODE_IDS));
            assertThat(patches).anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.AWAITING_SIGNAL_NODE_IDS));
            // NodeCounts patch present (counts bumps unconditionally)
            assertThat(patches).anyMatch(p -> p.path().length == 2 && p.path()[0].equals("nodes"));
        }

        @Test
        @DisplayName("Never NO_OP - NodeCounts.completed bumps even when keepInAwaiting=true")
        void neverNoOp() {
            StateSnapshot before = withNodeRunning().markNodeAwaitingSignal(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.resolveAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isNoOp()).isFalse();
        }

        @Test
        @DisplayName("Returns FALLBACK when epoch missing")
        void fallbackEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.resolveAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isFallback()).isTrue();
        }
    }

    // ==================================================================
    // R4 builders: AddReadyNode / RemoveReadyNode / RecordEdgeStatus
    // ==================================================================

    @Nested
    @DisplayName("AddReadyNodePatchBuilder")
    class AddReadyBuilder {
        private AddReadyNodePatchBuilder builder() {
            return new AddReadyNodePatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + readyNodeIds patch (2 patches)")
        void emitsTwoPatches() {
            StateSnapshot before = withNodeRunning();
            StateSnapshot after = before.addReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(2);
            assertThat(patches.get(0).path()).containsExactly("seq");
            assertThat(patches.get(1).path()[patches.get(1).path().length - 1])
                    .isEqualTo(PatchPaths.READY_NODE_IDS);
        }

        @Test
        @DisplayName("NO_OP when nodeId already in readyNodeIds")
        void noOpAlreadyReady() {
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.addReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("FALLBACK when epoch missing")
        void fallbackEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.addReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isFallback()).isTrue();
        }
    }

    @Nested
    @DisplayName("RemoveReadyNodePatchBuilder")
    class RemoveReadyBuilder {
        private RemoveReadyNodePatchBuilder builder() {
            return new RemoveReadyNodePatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + readyNodeIds patch when nodeId is ready")
        void emitsTwoPatches() {
            StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
            StateSnapshot after = before.removeReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isPatch()).isTrue();
            assertThat(result.patches().orElseThrow()).hasSize(2);
        }

        @Test
        @DisplayName("NO_OP when nodeId NOT in readyNodeIds (idempotent remove)")
        void noOpNotInReady() {
            StateSnapshot before = withNodeRunning(); // node is running, not ready
            StateSnapshot after = before.removeReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("FALLBACK when epoch missing (symmetry with AddReady)")
        void fallbackEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.removeReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, TRIGGER, EPOCH, NODE);
            assertThat(result.isFallback()).isTrue();
        }
    }

    @Nested
    @DisplayName("RecordDecisionBranchPatchBuilder")
    class DecisionBranchBuilder {
        private RecordDecisionBranchPatchBuilder builder() {
            return new RecordDecisionBranchPatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + decisionBranches map patch on first branch recorded")
        void emitsTwoPatches() {
            StateSnapshot before = withNodeRunning(); // epoch initialized
            StateSnapshot after = before.recordDecisionBranch(TRIGGER, NODE, EPOCH, "if").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, "if");
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(2);
            assertThat(patches.get(1).path()).containsExactly(
                    "dags", TRIGGER, "epochs", Integer.toString(EPOCH), "decisionBranches");
        }

        @Test
        @DisplayName("NO_OP when branch already in decisionBranches[nodeId] (idempotent)")
        void noOpWhenBranchAlreadyRecorded() {
            StateSnapshot before = withNodeRunning().recordDecisionBranch(TRIGGER, NODE, EPOCH, "if");
            StateSnapshot after = before.recordDecisionBranch(TRIGGER, NODE, EPOCH, "if").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, "if");
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("Emits patch when adding a NEW branch alongside an existing one")
        void emitsPatchOnAddingDifferentBranch() {
            StateSnapshot before = withNodeRunning().recordDecisionBranch(TRIGGER, NODE, EPOCH, "if");
            StateSnapshot after = before.recordDecisionBranch(TRIGGER, NODE, EPOCH, "elseif_0").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, TRIGGER, EPOCH, NODE, "elseif_0");
            assertThat(result.isPatch()).isTrue();
        }

        @Test
        @DisplayName("FALLBACK when epoch missing")
        void fallbackEpochMissing() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.recordDecisionBranch(TRIGGER, NODE, EPOCH, "if").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, TRIGGER, EPOCH, NODE, "if");
            assertThat(result.isFallback()).isTrue();
        }
    }

    @Nested
    @DisplayName("RecordEdgeStatusPatchBuilder")
    class EdgeBuilder {
        private RecordEdgeStatusPatchBuilder builder() {
            return new RecordEdgeStatusPatchBuilder(mapper);
        }

        @Test
        @DisplayName("Emits seq + edges[from->to] patch on increment")
        void emitsTwoPatches() {
            // Seed an existing edge so the parent map exists (path-init prerequisite)
            StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
            StateSnapshot after = before.incrementEdge("a", "b", "COMPLETED").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(before, after, "a", "b");
            assertThat(result.isPatch()).isTrue();
            var patches = result.patches().orElseThrow();
            assertThat(patches).hasSize(2);
            assertThat(patches.get(1).path()).containsExactly("edges", "a->b");
        }

        @Test
        @DisplayName("NO_OP when edge counts unchanged (e.g. unknown status)")
        void noOpSameCounts() {
            StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
            // No mutation between before/after - same reference
            JsonbPatchBuilder.Result result = builder().build(before, before, "a", "b");
            assertThat(result.isNoOp()).isTrue();
        }

        @Test
        @DisplayName("FALLBACK when before.edges is empty (path-init prerequisite for first edge)")
        void fallbackOnEmptyEdgesMap() {
            StateSnapshot empty = StateSnapshot.empty();
            StateSnapshot after = empty.incrementEdge("a", "b", "COMPLETED").withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder().build(empty, after, "a", "b");
            assertThat(result.isFallback()).isTrue();
        }
    }
}
