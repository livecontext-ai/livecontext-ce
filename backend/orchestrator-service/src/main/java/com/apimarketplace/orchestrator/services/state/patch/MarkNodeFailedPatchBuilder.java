package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Patch builder for the epoch-scoped {@code markNodeFailed(triggerId, nodeId,
 * epoch, durationMs)} mutator. Symmetric to {@link MarkNodeCompletedPatchBuilder}:
 * 3 EpochState set mutations (-running, -ready, +failed) + a NodeCounts.failed
 * bump.
 *
 * <p><b>Never NO_OP</b> - {@code NodeCounts.failed} bumps unconditionally.
 *
 * <p><b>Élide P2.3</b> - omits the {@code runningNodeIds} patch when elide ON.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodeFailedPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarkNodeFailedPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;
    private final TenantElideFlagResolver elideResolver;

    public MarkNodeFailedPatchBuilder(
            @Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper,
            TenantElideFlagResolver elideResolver) {
        this.stateSnapshotMapper = stateSnapshotMapper;
        this.elideResolver = elideResolver;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId,
                                          String tenantId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) {
            return JsonbPatchBuilder.Result.fallback();
        }

        boolean elide = elideResolver.isElideEnabled(tenantId);

        try {
            List<JsonbPatch> patches = new ArrayList<>(5);
            patches.add(new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.FAILED_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getFailedNodeIds())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds())));
            if (!elide) {
                patches.add(new JsonbPatch(
                        PatchPaths.epochSet(triggerId, epoch, PatchPaths.RUNNING_NODE_IDS),
                        stateSnapshotMapper.writeValueAsString(afterEpoch.getRunningNodeIds())));
            }
            StateSnapshot.NodeCounts counts = after.getNodes().get(nodeId);
            if (counts == null) return JsonbPatchBuilder.Result.fallback();
            patches.add(new JsonbPatch(
                    PatchPaths.nodeCounts(nodeId),
                    stateSnapshotMapper.writeValueAsString(counts)));
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodeFailedPatchBuilder] Jackson serialization failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
