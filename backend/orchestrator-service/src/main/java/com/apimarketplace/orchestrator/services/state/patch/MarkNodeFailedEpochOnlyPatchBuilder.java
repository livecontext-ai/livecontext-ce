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
 * Patch builder for {@code markNodeFailedEpochOnly} - failed twin of
 * {@link MarkNodeCompletedEpochOnlyPatchBuilder}. 3 EpochState sets
 * ({@code -running, -ready, +failed}), no NodeCounts.
 *
 * <p><b>NO_OP</b> when already in failed AND not in running/ready.
 * <p><b>Élide</b> - omits {@code runningNodeIds} when ON.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodeFailedEpochOnlyPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarkNodeFailedEpochOnlyPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;
    private final TenantElideFlagResolver elideResolver;

    public MarkNodeFailedEpochOnlyPatchBuilder(
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
        EpochState beforeEpoch = beforeDag.getEpochState(epoch);
        if (beforeEpoch.getFailedNodeIds().contains(nodeId)
                && !beforeEpoch.getRunningNodeIds().contains(nodeId)
                && !beforeEpoch.getReadyNodeIds().contains(nodeId)) {
            return JsonbPatchBuilder.Result.noOp();
        }

        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();

        boolean elide = elideResolver.isElideEnabled(tenantId);

        try {
            List<JsonbPatch> patches = new ArrayList<>(4);
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
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodeFailedEpochOnlyPatchBuilder] Jackson failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
