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
 * Patch builder for {@code markNodePartialFailure(triggerId, nodeId, epoch)} -
 * Phase 2.A split-async observability mutator. Touches ONLY {@code +partialFailedNodeIds}.
 * No NodeCounts. No running/ready/completed touch.
 *
 * <p><b>NO_OP</b> when {@code nodeId} already in {@code partialFailedNodeIds} -
 * pure idempotent set-add.
 *
 * <p><b>Élide</b> - not applicable: this builder doesn't touch {@code runningNodeIds}.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodePartialFailurePatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarkNodePartialFailurePatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public MarkNodePartialFailurePatchBuilder(
            @Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        EpochState beforeEpoch = beforeDag.getEpochState(epoch);
        if (beforeEpoch.getPartialFailedNodeIds().contains(nodeId)) {
            return JsonbPatchBuilder.Result.noOp();
        }
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(
                            PatchPaths.epochSet(triggerId, epoch, PatchPaths.PARTIAL_FAILED_NODE_IDS),
                            stateSnapshotMapper.writeValueAsString(afterEpoch.getPartialFailedNodeIds()))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodePartialFailurePatchBuilder] Jackson failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
