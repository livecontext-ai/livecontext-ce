package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX #2 - multi-edge {@link RecordEdgeStatusPatchBuilder#buildBatch} unit guards.
 *
 * <p>The batch edge writers ({@code recordEdgeStatusesBatch}/{@code recordEdgeStatuses})
 * must persist via the lock-free CAS path - one {@code edges.{key}} ASSIGN patch per
 * changed edge plus the {@code seq} bump - instead of a full snapshot rewrite. These
 * tests pin the builder's three outcomes: FALLBACK (parent map missing), NO_OP (nothing
 * changed), and a per-edge patch list.
 */
@DisplayName("FIX #2 - RecordEdgeStatusPatchBuilder.buildBatch (multi-edge CAS patch)")
class RecordEdgeStatusBatchBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RecordEdgeStatusPatchBuilder builder = new RecordEdgeStatusPatchBuilder(mapper);

    @Test
    @DisplayName("FALLBACK when the edges map is null/empty (jsonb_set can't create the parent)")
    void fallbackWhenEdgesMapEmpty() {
        StateSnapshot before = StateSnapshot.empty();                       // no edges yet
        StateSnapshot after = before.incrementEdge("a", "b", "COMPLETED");  // first edge ever

        JsonbPatchBuilder.Result result = builder.buildBatch(before, after, Set.of("a->b"));

        assertThat(result.isFallback())
                .as("first edge needs the full-rewrite to initialise the edges map")
                .isTrue();
    }

    @Test
    @DisplayName("NO_OP when no edge counts changed (only the seq would move)")
    void noOpWhenNoEdgeChanged() {
        StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
        StateSnapshot after = before.withIncrementedSeq();  // seq bump only; edges identical

        JsonbPatchBuilder.Result result = builder.buildBatch(before, after, Set.of("a->b"));

        assertThat(result.isNoOp()).isTrue();
    }

    @Test
    @DisplayName("Emits one edges.{key} patch per changed edge + the seq bump")
    void emitsPatchPerChangedEdge() {
        StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
        // a->b incremented again (existing key) AND c->d added (new key under existing map).
        StateSnapshot after = before
                .incrementEdge("a", "b", "COMPLETED")
                .incrementEdge("c", "d", "SKIPPED")
                .withIncrementedSeq();

        JsonbPatchBuilder.Result result = builder.buildBatch(before, after, Set.of("a->b", "c->d"));

        assertThat(result.patches()).isPresent();
        List<JsonbPatch> patches = result.patches().get();
        assertThat(patches).as("seq bump + 2 changed edges").hasSize(3);
        assertThat(patches.stream().anyMatch(p -> java.util.Arrays.equals(p.path(), PatchPaths.seq())))
                .as("seq patch present").isTrue();
        assertThat(patches.stream()
                .anyMatch(p -> java.util.Arrays.equals(p.path(), new String[] { "edges", "a->b" })))
                .as("a->b edge patched").isTrue();
        assertThat(patches.stream()
                .anyMatch(p -> java.util.Arrays.equals(p.path(), new String[] { "edges", "c->d" })))
                .as("c->d edge patched").isTrue();
    }

    @Test
    @DisplayName("Unchanged edges in the key set are skipped (only the moved one is patched)")
    void skipsUnchangedEdgesInKeySet() {
        StateSnapshot before = StateSnapshot.empty()
                .incrementEdge("a", "b", "COMPLETED")
                .incrementEdge("c", "d", "COMPLETED");
        StateSnapshot after = before.incrementEdge("c", "d", "COMPLETED").withIncrementedSeq();

        JsonbPatchBuilder.Result result = builder.buildBatch(before, after, Set.of("a->b", "c->d"));

        assertThat(result.patches()).isPresent();
        List<JsonbPatch> patches = result.patches().get();
        assertThat(patches).as("seq + only the c->d change").hasSize(2);
        assertThat(patches.stream()
                .anyMatch(p -> java.util.Arrays.equals(p.path(), new String[] { "edges", "a->b" })))
                .as("unchanged a->b not patched").isFalse();
    }
}
