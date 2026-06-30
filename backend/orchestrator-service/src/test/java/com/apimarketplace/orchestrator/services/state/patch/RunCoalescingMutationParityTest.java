package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit bug #2 - re-mutate-on-flush parity for the coalescer's CLOSURE path
 * ({@link RunCoalescingService#enqueueMutation}).
 *
 * <p>These tests do NOT use Postgres. Instead the {@code applyPatchesCas} mock
 * applies the folded patches to an in-memory JSON "row" using the SAME semantics
 * as the production composer:
 * <ul>
 *   <li>ASSIGN → replace the value at the path (jsonb_set replace).</li>
 *   <li>COMMUTATIVE_DELTA → read the int at the path (COALESCE 0) + delta.</li>
 * </ul>
 * and CAS-checks {@code expectedSeq} against the row's current seq. This lets us
 * assert the FINAL merged JSON content - the only way to prove the data-loss bug
 * is fixed (two same-path ASSIGN completions both survive) rather than just
 * asserting "a flush happened".
 */
@DisplayName("Audit bug #2 - coalescer re-mutate-on-flush parity (closure path)")
class RunCoalescingMutationParityTest {

    private static final String RUN = "run-parity";
    private static final String TRIGGER = "trigger:webhook";
    private static final int EPOCH = 0;

    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper mapper;
    private RunCoalescingService coalescer;
    private WorkflowRunRepository mockRepo;
    private JsonbPatchExecutor mockExecutor;

    /** In-memory "row": (seq, JSON string). */
    private final AtomicReference<String> rowJson = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong rowSeq = new java.util.concurrent.atomic.AtomicLong(0);

    /** Real parse-bridge backed by the test ObjectMapper. */
    private RunCoalescingService.MutationFlushBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        coalescer = new RunCoalescingService(meterRegistry, true);

        mockRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        mockExecutor = org.mockito.Mockito.mock(JsonbPatchExecutor.class);
        setField(coalescer, "runRepository", mockRepo);
        setField(coalescer, "patchExecutor", mockExecutor);

        // Seed the row: one DAG/epoch initialized, no completions yet.
        StateSnapshot seed = StateSnapshot.empty().ensureDagInitialized(TRIGGER, EPOCH);
        rowJson.set(mapper.writeValueAsString(seed));
        rowSeq.set(seed.getSeq());

        // Fresh-read returns the current (seq, json) row.
        org.mockito.Mockito.when(mockRepo.findSeqAndStateSnapshotByRunIdPublic(RUN))
                .thenAnswer(inv -> Optional.of(projection(rowSeq.get(), rowJson.get())));
        // Seq-only read used by the frozen-patch flush path.
        org.mockito.Mockito.lenient().when(mockRepo.findStateSnapshotSeqByRunIdPublic(RUN))
                .thenAnswer(inv -> Optional.of(rowSeq.get()));

        // applyPatchesCas applies the patches to the in-memory row with CAS check.
        org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                        org.mockito.ArgumentMatchers.eq(RUN),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    long expected = inv.getArgument(2);
                    long target = inv.getArgument(3);
                    @SuppressWarnings("unchecked")
                    List<JsonbPatch> patches = inv.getArgument(1);
                    if (rowSeq.get() != expected) {
                        return 0;  // CAS conflict
                    }
                    rowJson.set(applyPatchesInMemory(rowJson.get(), patches, target));
                    rowSeq.set(target);
                    return 1;
                });

        // Real bridge: parse via the mapper, no-op cache invalidate.
        bridge = new RunCoalescingService.MutationFlushBridge() {
            @Override
            public StateSnapshot parseBase(String runId, String json) {
                try {
                    return mapper.readValue(json, StateSnapshot.class);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void afterCoalescedFlush(String runId, long newSeq) {
                // no cache in this test
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = RunCoalescingService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static StateSnapshotSeqAndJsonProjection projection(long seq, String json) {
        return new StateSnapshotSeqAndJsonProjection() {
            @Override public Long getStateSnapshotSeq() { return seq; }
            @Override public String getStateSnapshot() { return json; }
        };
    }

    /**
     * Minimal in-memory jsonb_set emulation: ASSIGN replaces, DELTA adds to int.
     * Intermediate keys must already exist (mirrors Postgres jsonb_set; our
     * builders return fallback otherwise).
     */
    private String applyPatchesInMemory(String json, List<JsonbPatch> patches, long target) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(json);
            for (JsonbPatch p : patches) {
                String[] path = p.path();
                JsonNode parent = root;
                for (int i = 0; i < path.length - 1; i++) {
                    parent = parent.get(path[i]);
                    if (parent == null) {
                        throw new IllegalStateException("missing intermediate key " + path[i]);
                    }
                }
                ObjectNode parentObj = (ObjectNode) parent;
                String leaf = path[path.length - 1];
                if (p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA) {
                    long cur = parentObj.has(leaf) && !parentObj.get(leaf).isNull()
                            ? parentObj.get(leaf).asLong() : 0L;
                    parentObj.put(leaf, cur + Long.parseLong(p.jsonValue()));
                } else {
                    parentObj.set(leaf, mapper.readTree(p.jsonValue()));
                }
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The recompute closure for "mark node X completed (epoch-only set add)" -
     * mirrors what StateSnapshotService builds for the coalescer. Emits an ASSIGN
     * on the full {@code epochs.E.completedNodeIds} set serialized from the
     * post-mutation snapshot, plus a seq patch.
     */
    private Function<StateSnapshot, RunCoalescingService.RecomputeOutput> completeNode(String nodeId) {
        return base -> {
            StateSnapshot advanced = base.markNodeCompleted(TRIGGER, nodeId, EPOCH);
            DagState dag = advanced.getDags().get(TRIGGER);
            if (dag == null || dag.getEpochState(EPOCH) == null) {
                return RunCoalescingService.RecomputeOutput.fallback();
            }
            try {
                JsonbPatch setPatch = JsonbPatch.assignment(
                        PatchPaths.epochSet(TRIGGER, EPOCH, PatchPaths.COMPLETED_NODE_IDS),
                        mapper.writeValueAsString(dag.getEpochState(EPOCH).getCompletedNodeIds()));
                JsonbPatch seqPatch = JsonbPatch.assignment(PatchPaths.seq(),
                        Long.toString(advanced.withIncrementedSeq().getSeq()));
                return RunCoalescingService.RecomputeOutput.of(List.of(setPatch, seqPatch), advanced);
            } catch (Exception e) {
                return RunCoalescingService.RecomputeOutput.fallback();
            }
        };
    }

    private java.util.Set<String> completedNodeIdsInRow() throws Exception {
        JsonNode root = mapper.readTree(rowJson.get());
        JsonNode set = root.path("dags").path(TRIGGER).path("epochs")
                .path(Integer.toString(EPOCH)).path(PatchPaths.COMPLETED_NODE_IDS);
        java.util.Set<String> out = new java.util.HashSet<>();
        if (set instanceof ArrayNode arr) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    @Test
    @DisplayName("Two same-path ASSIGN completions both survive - no last-writer-wins loss (THE bug #2 regression)")
    void concurrentSamePathAssignsBothSurvive() throws Exception {
        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);

        // Two split items each enqueue a completion against the SAME stale base.
        // Pre-fix: each freezes [itemA] / [itemB] from the empty base, force-flush
        // commits A then B → B overwrites A. Post-fix: the second closure is
        // re-run against the base that already has A → folds to {A, B}.
        CompletableFuture<Void> fA = coalescer.enqueueMutation(RUN, completeNode("itemA"), "markNodeCompleted");
        CompletableFuture<Void> fB = coalescer.enqueueMutation(RUN, completeNode("itemB"), "markNodeCompleted");

        coalescer.closeCoalescing(RUN);  // drains + flushes the batch

        assertThat(fA).isCompletedWithValue(null);
        assertThat(fB).isCompletedWithValue(null);
        assertThat(completedNodeIdsInRow())
                .as("both itemA AND itemB must be present - neither completion lost")
                .containsExactlyInAnyOrder("itemA", "itemB");
    }

    @Test
    @DisplayName("Demonstrates the bug #2 the closure path fixes: the OLD frozen-patch path "
            + "(enqueuePatch with same-path ASSIGNs built from a stale base) LOSES the first completion")
    void frozenPatchPathLosesFirstCompletion() throws Exception {
        coalescer.openCoalescing(RUN);

        // Both items build their ASSIGN from the SAME stale empty base (the
        // real bug scenario): item A freezes completedNodeIds=["itemA"], item B
        // freezes completedNodeIds=["itemB"]. Same-path ASSIGN → force-flush A,
        // then commit B → B overwrites A. THIS is the data-loss the closure path
        // (enqueueMutation) prevents by re-running the builder on the running base.
        StateSnapshot stale = mapper.readValue(rowJson.get(), StateSnapshot.class);
        StateSnapshot afterA = stale.markNodeCompleted(TRIGGER, "itemA", EPOCH);
        StateSnapshot afterB = stale.markNodeCompleted(TRIGGER, "itemB", EPOCH);  // from STALE base, not afterA
        JsonbPatch assignA = JsonbPatch.assignment(
                PatchPaths.epochSet(TRIGGER, EPOCH, PatchPaths.COMPLETED_NODE_IDS),
                mapper.writeValueAsString(afterA.getDags().get(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds()));
        JsonbPatch assignB = JsonbPatch.assignment(
                PatchPaths.epochSet(TRIGGER, EPOCH, PatchPaths.COMPLETED_NODE_IDS),
                mapper.writeValueAsString(afterB.getDags().get(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds()));

        coalescer.enqueuePatch(RUN, assignA, PatchClass.OpKind.ASSIGN);
        coalescer.enqueuePatch(RUN, assignB, PatchClass.OpKind.ASSIGN);  // force-flush A, then B clobbers
        coalescer.closeCoalescing(RUN);

        assertThat(completedNodeIdsInRow())
                .as("frozen-patch path loses itemA - last-writer-wins clobber (the bug)")
                .containsExactly("itemB");
    }

    @Test
    @DisplayName("Read-after-write: after the coalesced flush the row reflects the merge at newSeq")
    void rowReflectsMergeAtNewSeq() throws Exception {
        long startSeq = rowSeq.get();
        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);

        CompletableFuture<Void> f = coalescer.enqueueMutation(RUN, completeNode("itemA"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);

        assertThat(f).isCompletedWithValue(null);
        assertThat(rowSeq.get()).as("seq bumped exactly once").isEqualTo(startSeq + 1);
        assertThat(completedNodeIdsInRow()).containsExactly("itemA");

        coalescer.closeCoalescing(RUN);
    }

    @Test
    @DisplayName("Three sequential completions across two flushes all survive (compose on running base)")
    void threeCompletionsAllSurvive() throws Exception {
        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);

        // First item flushes alone.
        CompletableFuture<Void> f1 = coalescer.enqueueMutation(RUN, completeNode("a"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);
        // Next two are enqueued then flushed together.
        CompletableFuture<Void> f2 = coalescer.enqueueMutation(RUN, completeNode("b"), "markNodeCompleted");
        CompletableFuture<Void> f3 = coalescer.enqueueMutation(RUN, completeNode("c"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);
        coalescer.closeCoalescing(RUN);

        assertThat(f1).isCompletedWithValue(null);
        assertThat(f2).isCompletedWithValue(null);
        assertThat(f3).isCompletedWithValue(null);
        assertThat(completedNodeIdsInRow()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("DELTA + DELTA on the same path still merges additively (no regression)")
    void deltaPlusDeltaStillMergesAdditively() throws Exception {
        // Seed a counter at nodes.X.completed = 0 by adding the node container.
        ObjectNode root = (ObjectNode) mapper.readTree(rowJson.get());
        ObjectNode nodes = (ObjectNode) root.get("nodes");
        ObjectNode x = nodes.putObject("X");
        x.put("completed", 0);
        rowJson.set(mapper.writeValueAsString(root));

        Function<StateSnapshot, RunCoalescingService.RecomputeOutput> deltaPlus1 =
                base -> RunCoalescingService.RecomputeOutput.of(
                        List.of(JsonbPatch.commutativeDelta(PatchPaths.nodeCountsField("X", "completed"), 1L)),
                        base);  // base unchanged for this simplified delta closure

        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);
        CompletableFuture<Void> f1 = coalescer.enqueueMutation(RUN, deltaPlus1, "incr");
        CompletableFuture<Void> f2 = coalescer.enqueueMutation(RUN, deltaPlus1, "incr");
        CompletableFuture<Void> f3 = coalescer.enqueueMutation(RUN, deltaPlus1, "incr");
        coalescer.flushPendingMutations(RUN);
        coalescer.closeCoalescing(RUN);

        assertThat(f1).isCompletedWithValue(null);
        assertThat(f2).isCompletedWithValue(null);
        assertThat(f3).isCompletedWithValue(null);
        JsonNode finalRoot = mapper.readTree(rowJson.get());
        assertThat(finalRoot.path("nodes").path("X").path("completed").asLong())
                .as("3 × +1 folded into one +3 - additive merge preserved")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("CAS conflict re-runs the WHOLE recompute chain against the peer-bumped base")
    void casConflictReRunsChainAgainstFreshBase() throws Exception {
        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);

        // Simulate a peer commit between the flush's read and its CAS: on the
        // first applyPatchesCas, bump the row out from under us so CAS fails,
        // THEN let the retry read the peer-updated base and succeed. The peer
        // added "peerNode"; our retry must compose itemA on top → {peerNode, itemA}.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                        org.mockito.ArgumentMatchers.eq(RUN),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    long expected = inv.getArgument(2);
                    long target = inv.getArgument(3);
                    @SuppressWarnings("unchecked")
                    List<JsonbPatch> patches = inv.getArgument(1);
                    if (calls.getAndIncrement() == 0) {
                        // Peer commit: add peerNode + bump seq, then reject our CAS.
                        StateSnapshot peer = mapper.readValue(rowJson.get(), StateSnapshot.class)
                                .markNodeCompleted(TRIGGER, "peerNode", EPOCH).withIncrementedSeq();
                        rowJson.set(mapper.writeValueAsString(peer));
                        rowSeq.set(peer.getSeq());
                        return 0;  // CAS conflict (expected != current)
                    }
                    if (rowSeq.get() != expected) return 0;
                    rowJson.set(applyPatchesInMemory(rowJson.get(), patches, target));
                    rowSeq.set(target);
                    return 1;
                });

        CompletableFuture<Void> f = coalescer.enqueueMutation(RUN, completeNode("itemA"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);
        coalescer.closeCoalescing(RUN);

        assertThat(f).isCompletedWithValue(null);
        assertThat(completedNodeIdsInRow())
                .as("retry recomposed itemA on top of the peer's committed peerNode")
                .containsExactlyInAnyOrder("peerNode", "itemA");
        assertThat(meterRegistry.counter("orchestrator.coalesce.cas_conflict_count").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fallback request (missing epoch) → flush fails, future completes exceptionally → caller falls back to pessimistic")
    void fallbackRequestRoutesToPessimistic() throws Exception {
        // Seed a row WITHOUT the epoch so markNodeCompleted's builder asks for fallback.
        StateSnapshot noEpoch = StateSnapshot.empty();
        rowJson.set(mapper.writeValueAsString(noEpoch));
        rowSeq.set(noEpoch.getSeq());

        coalescer.openCoalescing(RUN);
        coalescer.bindMutationBridge(RUN, bridge);
        CompletableFuture<Void> f = coalescer.enqueueMutation(RUN, completeNode("itemA"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);
        coalescer.closeCoalescing(RUN);

        assertThat(f).isCompletedExceptionally();
        // No write happened - row untouched, caller must pessimistic-fallback.
        assertThat(completedNodeIdsInRow()).isEmpty();
    }

    @Test
    @DisplayName("Mutation flush with no bridge bound → POISON + future fails (no NPE)")
    void noBridgePoisons() {
        coalescer.openCoalescing(RUN);
        // Deliberately DO NOT bind the bridge.
        CompletableFuture<Void> f = coalescer.enqueueMutation(RUN, completeNode("itemA"), "markNodeCompleted");
        coalescer.flushPendingMutations(RUN);
        coalescer.closeCoalescing(RUN);

        assertThat(f).isCompletedExceptionally();
        assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                .isEqualTo(1.0);
    }
}
