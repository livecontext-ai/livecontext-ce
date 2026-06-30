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
 * Patch builder for {@code resolveAwaitingSignal(triggerId, nodeId, epoch,
 * durationMs, keepInAwaiting)} (StateSnapshot.java:678).
 *
 * <p>Mutator semantics:
 * <ul>
 *   <li>EpochState: {@code +completedNodeIds}; {@code -awaitingSignalNodeIds}
 *       only when {@code keepInAwaiting=false}</li>
 *   <li>NodeCounts: bumps {@code completed} unconditionally
 *       ({@code incrementWithTiming("COMPLETED", durationMs)})</li>
 * </ul>
 *
 * <p><b>Never NO_OP</b> - counts bump unconditionally (even with
 * {@code keepInAwaiting=true} the mutator still increments NodeCounts).
 *
 * <p><b>Élide</b> - not applicable: this builder doesn't touch
 * {@code runningNodeIds}.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class ResolveAwaitingSignalPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(ResolveAwaitingSignalPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public ResolveAwaitingSignalPatchBuilder(
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
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.COMPLETED_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getCompletedNodeIds())));
            // Always emit awaitingSignalNodeIds: regardless of keepInAwaiting,
            // the after-state set is well-defined and writing the same value is safe.
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.AWAITING_SIGNAL_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getAwaitingSignalNodeIds())));
            StateSnapshot.NodeCounts counts = after.getNodes().get(nodeId);
            if (counts == null) return JsonbPatchBuilder.Result.fallback();
            patches.add(new JsonbPatch(
                    PatchPaths.nodeCounts(nodeId),
                    stateSnapshotMapper.writeValueAsString(counts)));
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[ResolveAwaitingSignalPatchBuilder] Jackson failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
