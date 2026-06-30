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
 * Patch builder for {@code addReadyNode(runId, nodeId)} flat - adds a node to
 * {@code readyNodeIds} of the DAG resolved via {@code getDefaultTriggerId()}
 * at its current epoch.
 *
 * <p>Mutator semantics (StateSnapshot.java:633 + flat indirection at 815):
 * single-set mutation {@code +readyNodeIds}. No NodeCounts touch. No élide
 * applicability (does not write {@code runningNodeIds}).
 *
 * <p><b>NO_OP</b> when {@code nodeId} already in {@code readyNodeIds} -
 * pure idempotent set-add.
 *
 * <p><b>FALLBACK</b> when the resolved DAG has no current-epoch state in
 * {@code before} (path-init prerequisite for jsonb_set).
 *
 * <p>The caller (StateSnapshotService) is responsible for resolving the
 * triggerId + epoch via {@code before.getDefaultTriggerId()} +
 * {@code before.getDagState(triggerId).getCurrentEpoch()} so the builder
 * can stay parameterized on (triggerId, epoch) and reuse the unified
 * pattern with epoch-scoped builders.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class AddReadyNodePatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(AddReadyNodePatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public AddReadyNodePatchBuilder(@Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        EpochState beforeEpoch = beforeDag.getEpochState(epoch);
        if (beforeEpoch.getReadyNodeIds().contains(nodeId)) {
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
            log.warn("[AddReadyNodePatchBuilder] Jackson failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
