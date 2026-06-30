package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Patch builder for the epoch-scoped {@code markNodeSkipped(triggerId, nodeId,
 * epoch)} mutator.
 *
 * <p>Mutator semantics (StateSnapshot.java:579, EpochState.java:153) - only
 * 2 EpochState sets touched: {@code -readyNodeIds, +skippedNodeIds}. Does NOT
 * touch {@code runningNodeIds}. Plus {@code NodeCounts.skipped} bumps.
 *
 * <p><b>Never NO_OP</b> - counts bump unconditionally.
 *
 * <p><b>Élide</b> - not applicable: this builder doesn't write {@code runningNodeIds}.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodeSkippedPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarkNodeSkippedPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public MarkNodeSkippedPatchBuilder(
            @Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();

        try {
            List<JsonbPatch> patches = new ArrayList<>(4);
            patches.add(new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.SKIPPED_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getSkippedNodeIds())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds())));
            StateSnapshot.NodeCounts counts = after.getNodes().get(nodeId);
            if (counts == null) return JsonbPatchBuilder.Result.fallback();
            patches.add(new JsonbPatch(
                    PatchPaths.nodeCounts(nodeId),
                    stateSnapshotMapper.writeValueAsString(counts)));
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodeSkippedPatchBuilder] Jackson serialization failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
