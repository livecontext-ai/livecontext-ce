package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Patch builder for the {@code incrementNodeCountsOnly(nodeId, status, count)}
 * mutator.
 *
 * <h2>Mutator semantics (validated against source)</h2>
 *
 * <p>{@link StateSnapshot#incrementNodeCountsOnly(String, String, int)}
 * (StateSnapshot.java:598) mutates ONLY {@code nodes[nodeId]} - no EpochState
 * touch. {@code count <= 0} is a Java-side no-op (returns {@code this}).
 *
 * <h2>No-op</h2>
 *
 * <p>If {@code count <= 0} or if {@code before.getNodes().get(nodeId).equals(after.getNodes().get(nodeId))},
 * return NO_OP. The Java mutator already checks count<=0 - the builder
 * defends against the rare equality case (status string normalization or
 * unknown status).
 *
 * <h2>Elide</h2>
 *
 * <p>Not applicable - no {@code runningNodeIds} touch.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class IncrementNodeCountsOnlyPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(IncrementNodeCountsOnlyPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public IncrementNodeCountsOnlyPatchBuilder(
            @Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String nodeId) {
        // PATH-INIT prerequisite: jsonb_set with create_missing=true creates only
        // the LAST missing key, not intermediate parents. If `nodes` is empty in
        // the JSONB (brand-new run, never wrote any node yet), the patch on
        // {nodes, nodeId} would silently no-op while the seq patch succeeds -
        // leaving DB and TxCache divergent. Fall back to full rewrite, which
        // serializes the entire `nodes` map and creates the parent key.
        if (before.getNodes() == null || before.getNodes().isEmpty()) {
            return JsonbPatchBuilder.Result.fallback();
        }
        StateSnapshot.NodeCounts beforeCounts = before.getNodes().get(nodeId);
        StateSnapshot.NodeCounts afterCounts = after.getNodes().get(nodeId);
        if (afterCounts == null) {
            // Should never happen - defensive fallback.
            return JsonbPatchBuilder.Result.fallback();
        }
        if (afterCounts.equals(beforeCounts)) {
            // Java mutator returned `this` (count<=0 or unknown status).
            return JsonbPatchBuilder.Result.noOp();
        }
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(PatchPaths.nodeCounts(nodeId),
                            stateSnapshotMapper.writeValueAsString(afterCounts))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[IncrementNodeCountsOnlyPatchBuilder] Jackson serialization failed for nodeId={}, fallback",
                    nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
