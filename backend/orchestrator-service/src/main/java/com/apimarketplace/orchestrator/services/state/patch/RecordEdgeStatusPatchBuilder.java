package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Patch builder for {@code recordEdgeStatus(runId, from, to, status)} -
 * increments the {@code EdgeCounts} for the {@code "from->to"} edge key in
 * the global {@code edges} map.
 *
 * <p>Mutator semantics (StateSnapshot.java:1023): single-path mutation
 * {@code edges[from->to] = current.increment(status)}. No EpochState touch.
 *
 * <p>The path is {@code {edges, from->to}} - the {@code "->"} delimiter is
 * an opaque substring of the JSON key (Postgres treats it as bytes); the
 * defensive quoting in {@link JsonbPatch#toPostgresArrayLiteral()} handles
 * any embedded special chars.
 *
 * <p><b>NO_OP</b> when {@code afterEdge.equals(beforeEdge)} (defensive - Java
 * mutator may return identity if status is unknown). Same shape as
 * {@link IncrementNodeCountsOnlyPatchBuilder}.
 *
 * <p><b>FALLBACK</b> when {@code before.edges} is null/empty (path-init
 * prerequisite for first edge: {@code jsonb_set} create_missing only creates
 * the LAST missing key, not the parent {@code edges} map).
 *
 * <p><b>Élide</b> - not applicable: this builder doesn't touch {@code runningNodeIds}.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class RecordEdgeStatusPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(RecordEdgeStatusPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public RecordEdgeStatusPatchBuilder(@Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String from, String to) {
        if (before.getEdges() == null || before.getEdges().isEmpty()) {
            return JsonbPatchBuilder.Result.fallback();
        }
        String edgeKey = from + "->" + to;
        StateSnapshot.EdgeCounts beforeEdge = before.getEdges().get(edgeKey);
        StateSnapshot.EdgeCounts afterEdge = after.getEdges().get(edgeKey);
        if (afterEdge == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        if (afterEdge.equals(beforeEdge)) {
            return JsonbPatchBuilder.Result.noOp();
        }
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(
                            new String[] { "edges", edgeKey },
                            stateSnapshotMapper.writeValueAsString(afterEdge))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[RecordEdgeStatusPatchBuilder] Jackson failed for edge={}, fallback", edgeKey, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }

    /**
     * Multi-edge variant for {@code recordEdgeStatusesBatch}/{@code recordEdgeStatuses}.
     * Emits one {@code edges.{edgeKey}} ASSIGN patch per <em>changed</em> edge plus the
     * {@code seq} bump, so the batch persists via the lock-free CAS path instead of a
     * full snapshot rewrite. Same path-init prerequisite as {@link #build}: FALLBACK when
     * {@code before.edges} is null/empty (jsonb_set create_missing only creates the last
     * missing key, not the parent {@code edges} map). Per-edge no-ops (unchanged counts)
     * and keys the mutator never materialised are skipped; an all-no-op batch returns
     * NO_OP. {@code edgeKeys} are the {@code "from->to"} keys touched by the mutator.
     */
    public JsonbPatchBuilder.Result buildBatch(StateSnapshot before, StateSnapshot after,
                                               Set<String> edgeKeys) {
        if (before.getEdges() == null || before.getEdges().isEmpty()) {
            return JsonbPatchBuilder.Result.fallback();
        }
        List<JsonbPatch> patches = new ArrayList<>(edgeKeys.size() + 1);
        patches.add(new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())));
        try {
            for (String edgeKey : edgeKeys) {
                StateSnapshot.EdgeCounts beforeEdge = before.getEdges().get(edgeKey);
                StateSnapshot.EdgeCounts afterEdge = after.getEdges().get(edgeKey);
                if (afterEdge == null || afterEdge.equals(beforeEdge)) {
                    continue;  // mutator never materialised this key, or no net change
                }
                patches.add(new JsonbPatch(
                        new String[] { "edges", edgeKey },
                        stateSnapshotMapper.writeValueAsString(afterEdge)));
            }
        } catch (JsonProcessingException e) {
            log.warn("[RecordEdgeStatusPatchBuilder] Jackson failed for batch edges, fallback", e);
            return JsonbPatchBuilder.Result.fallback();
        }
        if (patches.size() == 1) {
            return JsonbPatchBuilder.Result.noOp();  // only the seq bump → nothing changed
        }
        return JsonbPatchBuilder.Result.patch(patches);
    }
}
