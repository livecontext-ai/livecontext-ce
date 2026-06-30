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
 * Patch builder for the epoch-scoped {@code markNodeAwaitingSignal(triggerId,
 * nodeId, epoch)} mutator.
 *
 * <h2>Mutator semantics (validated against source)</h2>
 *
 * <p>{@link StateSnapshot#markNodeAwaitingSignal(String, String, int)}
 * (StateSnapshot.java:645) mutates ONLY the EpochState sets - no NodeCounts:
 * <ol>
 *   <li>{@code dags[triggerId].epochs[epoch].runningNodeIds.remove(nodeId)}</li>
 *   <li>{@code dags[triggerId].epochs[epoch].readyNodeIds.remove(nodeId)}</li>
 *   <li>{@code dags[triggerId].epochs[epoch].awaitingSignalNodeIds.add(nodeId)}</li>
 * </ol>
 *
 * <h2>No-op</h2>
 *
 * <p>If {@code nodeId} is already in {@code awaitingSignalNodeIds} of {@code before},
 * AND not in {@code runningNodeIds} or {@code readyNodeIds}, the mutator
 * produces a structurally-identical state - return NO_OP and skip the DB
 * write entirely.
 *
 * <h2>Elide (P2.3)</h2>
 *
 * <p>Same contract as {@link MarkNodeCompletedPatchBuilder}: when elide is on
 * for the tenant, omit any {@code runningNodeIds} patch.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodeAwaitingSignalPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(MarkNodeAwaitingSignalPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;
    private final TenantElideFlagResolver elideResolver;

    public MarkNodeAwaitingSignalPatchBuilder(
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

        // No-op detection: nodeId already in awaiting AND not in running/ready
        if (beforeEpoch.getAwaitingSignalNodeIds().contains(nodeId)
                && !beforeEpoch.getRunningNodeIds().contains(nodeId)
                && !beforeEpoch.getReadyNodeIds().contains(nodeId)) {
            return JsonbPatchBuilder.Result.noOp();
        }

        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) {
            return JsonbPatchBuilder.Result.fallback();
        }

        boolean elide = elideResolver.isElideEnabled(tenantId);

        try {
            List<JsonbPatch> patches = new ArrayList<>(4);
            patches.add(new JsonbPatch(PatchPaths.seq(),
                    Long.toString(after.getSeq())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.AWAITING_SIGNAL_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getAwaitingSignalNodeIds())));
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
            log.warn("[MarkNodeAwaitingSignalPatchBuilder] Jackson serialization failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
