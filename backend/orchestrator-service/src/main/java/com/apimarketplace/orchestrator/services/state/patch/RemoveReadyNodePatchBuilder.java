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

import java.util.List;

/**
 * Patch builder for {@code removeReadyNode(runId, nodeId)} flat - removes a
 * node from {@code readyNodeIds}. Single-set mutation {@code -readyNodeIds}.
 *
 * <p><b>NO_OP</b> when {@code nodeId} NOT in {@code readyNodeIds} -
 * pure idempotent set-remove.
 *
 * <p><b>FALLBACK</b> when DAG has no current epoch state.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class RemoveReadyNodePatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(RemoveReadyNodePatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public RemoveReadyNodePatchBuilder(@Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        EpochState beforeEpoch = beforeDag.getEpochState(epoch);
        if (!beforeEpoch.getReadyNodeIds().contains(nodeId)) {
            return JsonbPatchBuilder.Result.noOp();
        }
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(
                            PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                            stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds()))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[RemoveReadyNodePatchBuilder] Jackson failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
