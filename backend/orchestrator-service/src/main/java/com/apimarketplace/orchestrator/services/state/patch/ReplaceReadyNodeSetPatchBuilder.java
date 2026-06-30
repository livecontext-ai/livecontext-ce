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
 * Patch builder for the DAG-scoped ready-set merge
 * ({@code mergeReadyNodesAfterExecution}) - replaces the
 * {@code dags.{triggerId}.epochs.{epoch}.readyNodeIds} array with the post-merge
 * set. Mirrors {@link AddReadyNodePatchBuilder} / {@link RemoveReadyNodePatchBuilder}
 * exactly (same path, same serializer, same {@code seq} bump) but is keyed on the
 * whole-set delta produced by the remove-executed-node + add-N-successors mutation,
 * so it never trips the single-node {@code NO_OP} short-circuit those builders use.
 *
 * <ul>
 *   <li><b>FALLBACK</b> - the resolved DAG/epoch has no {@link EpochState}
 *       in {@code before} or {@code after} (path-init prerequisite for
 *       {@code jsonb_set} is missing), or Jackson serialization fails.</li>
 *   <li><b>NO_OP</b> - the ready-set is byte-for-byte unchanged (executed
 *       node wasn't ready AND every successor was already ready).</li>
 *   <li><b>PATCH</b> - otherwise: one {@code seq} patch + one
 *       {@code readyNodeIds} set-replacement patch.</li>
 * </ul>
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class ReplaceReadyNodeSetPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReplaceReadyNodeSetPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public ReplaceReadyNodeSetPatchBuilder(@Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch) {
        DagState beforeDag = before.getDags().get(triggerId);
        EpochState beforeEpoch = beforeDag != null ? beforeDag.getEpochState(epoch) : null;
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (beforeEpoch == null || afterEpoch == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        if (beforeEpoch.getReadyNodeIds().equals(afterEpoch.getReadyNodeIds())) {
            return JsonbPatchBuilder.Result.noOp();
        }
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(
                            PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                            stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds()))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[ReplaceReadyNodeSetPatchBuilder] Jackson failed for {}-{}, fallback",
                    triggerId, epoch, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
