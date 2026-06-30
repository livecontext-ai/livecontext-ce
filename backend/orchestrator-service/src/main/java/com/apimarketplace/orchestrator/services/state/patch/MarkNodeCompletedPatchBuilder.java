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
 * Patch builder for the epoch-scoped {@code markNodeCompleted(triggerId, nodeId,
 * epoch, durationMs)} mutator.
 *
 * <h2>Mutator semantics (validated against source)</h2>
 *
 * <p>{@link StateSnapshot#markNodeCompleted(String, String, int, long)}
 * (StateSnapshot.java:490) mutates:
 * <ol>
 *   <li>{@code dags[triggerId].epochs[epoch].completedNodeIds.add(nodeId)}</li>
 *   <li>{@code dags[triggerId].epochs[epoch].runningNodeIds.remove(nodeId)}</li>
 *   <li>{@code dags[triggerId].epochs[epoch].readyNodeIds.remove(nodeId)}</li>
 *   <li>{@code nodes[nodeId] = NodeCounts.incrementWithTiming("COMPLETED", durationMs)}</li>
 * </ol>
 *
 * <p>Plus the {@code seq} bump applied by {@code saveSnapshotPatched} before
 * calling the builder.
 *
 * <h2>No-op</h2>
 *
 * <p>NEVER. {@code NodeCounts.completed} is incremented unconditionally - even
 * if {@code nodeId} is already in {@code completedNodeIds}, the call is the
 * caller's signal "another execution finished" and bumps the counter. Returning
 * NO_OP would silently lose count increments on idempotent retries.
 *
 * <h2>Fallback</h2>
 *
 * <p>If {@code before} does not contain the epoch (epoch hasn't been opened in
 * the snapshot yet), Postgres {@code jsonb_set} cannot create the intermediate
 * objects to materialize a fresh epoch - fall back to full rewrite.
 *
 * <h2>Elide (P2.3)</h2>
 *
 * <p>When {@link TenantElideFlagResolver#isElideEnabled} returns true for the
 * tenant, the {@code runningNodeIds} patch is omitted entirely. The serializer
 * suppresses this field on the full-rewrite path (see
 * {@code EpochStateRunningElideSerializer}); the patch path mirrors that
 * decision so the JSONB shape is identical between the two paths.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class MarkNodeCompletedPatchBuilder {

    /**
     * Plan v4 §2t cooperative DELTA+ASSIGN - emits the same effect as
     * {@link #build} but writes the NodeCounts as sub-path patches:
     *   - DELTA +1 on {@code nodes.{X}.completed} (merge-friendly under fan-out)
     *   - ASSIGN on running/failed/skipped/totalExecutionTimeMs/lastEndTimeMs/lastExecutionTimeMs
     *
     * <p>Under N items completing the same node, the DELTA half merges
     * (N `+1` → 1 `+N` in coalescer), the ASSIGN half force-flushes per-item.
     * The throughput unlock is the DELTA merge - ASSIGN is unchanged.
     *
     * <p>Caller responsibility: this MUST run via a path that uses the
     * coalescer (saveSnapshotPatchedCas under the coalescer). If called
     * outside the coalescer, the DELTA emits a SQL `+1` that races against
     * concurrent writers - the seq-CAS predicate makes this safe (row-level
     * serialization in Postgres) but the merge benefit is lost.
     *
     * <p>Same fallback semantics as {@link #build} - missing epoch / missing
     * NodeCounts → fallback to full rewrite.
     */
    public JsonbPatchBuilder.Result buildWithDeltaCounter(
            StateSnapshot before, StateSnapshot after,
            String triggerId, int epoch, String nodeId, String tenantId) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        DagState afterDag = after.getDags().get(triggerId);
        if (afterDag == null) return JsonbPatchBuilder.Result.fallback();
        EpochState afterEpoch = afterDag.getEpochState(epoch);
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();

        boolean elide = elideResolver.isElideEnabled(tenantId);
        StateSnapshot.NodeCounts counts = after.getNodes().get(nodeId);
        if (counts == null) return JsonbPatchBuilder.Result.fallback();

        try {
            List<JsonbPatch> patches = new ArrayList<>(10);
            patches.add(JsonbPatch.assignment(PatchPaths.seq(),
                    Long.toString(after.getSeq())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.COMPLETED_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getCompletedNodeIds())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds())));
            if (!elide) {
                patches.add(JsonbPatch.assignment(
                        PatchPaths.epochSet(triggerId, epoch, PatchPaths.RUNNING_NODE_IDS),
                        stateSnapshotMapper.writeValueAsString(afterEpoch.getRunningNodeIds())));
            }
            // The plan v4 §2t split - DELTA on completed counter, ASSIGN on the rest.
            patches.add(JsonbPatch.commutativeDelta(
                    PatchPaths.nodeCountsField(nodeId, "completed"), 1L));
            // Other NodeCounts sub-fields as ASSIGN (full-replace per sub-path):
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "running"),
                    Integer.toString(counts.running())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "failed"),
                    Integer.toString(counts.failed())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "skipped"),
                    Integer.toString(counts.skipped())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "totalExecutionTimeMs"),
                    Long.toString(counts.totalExecutionTimeMs())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "lastEndTimeMs"),
                    Long.toString(counts.lastEndTimeMs())));
            patches.add(JsonbPatch.assignment(
                    PatchPaths.nodeCountsField(nodeId, "lastExecutionTimeMs"),
                    Long.toString(counts.lastExecutionTimeMs())));
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodeCompletedPatchBuilder] cooperative DELTA serialize failed for {}-{}-{}, fallback",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }


    private static final Logger log = LoggerFactory.getLogger(MarkNodeCompletedPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;
    private final TenantElideFlagResolver elideResolver;

    public MarkNodeCompletedPatchBuilder(
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
            // Epoch hasn't been materialized in the JSONB yet - jsonb_set cannot
            // create the intermediate map entry. Full rewrite handles this.
            return JsonbPatchBuilder.Result.fallback();
        }
        DagState afterDag = after.getDags().get(triggerId);
        if (afterDag == null) {
            // Should not happen for a markNodeCompleted call (mutator just ran on triggerId)
            return JsonbPatchBuilder.Result.fallback();
        }
        EpochState afterEpoch = afterDag.getEpochState(epoch);
        if (afterEpoch == null) {
            return JsonbPatchBuilder.Result.fallback();
        }

        boolean elide = elideResolver.isElideEnabled(tenantId);

        try {
            List<JsonbPatch> patches = new ArrayList<>(5);
            patches.add(new JsonbPatch(PatchPaths.seq(),
                    Long.toString(after.getSeq())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.COMPLETED_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getCompletedNodeIds())));
            patches.add(new JsonbPatch(
                    PatchPaths.epochSet(triggerId, epoch, PatchPaths.READY_NODE_IDS),
                    stateSnapshotMapper.writeValueAsString(afterEpoch.getReadyNodeIds())));
            if (!elide) {
                patches.add(new JsonbPatch(
                        PatchPaths.epochSet(triggerId, epoch, PatchPaths.RUNNING_NODE_IDS),
                        stateSnapshotMapper.writeValueAsString(afterEpoch.getRunningNodeIds())));
            }
            // NodeCounts replacement - full record, since multiple sub-fields change atomically
            StateSnapshot.NodeCounts counts = after.getNodes().get(nodeId);
            if (counts == null) {
                // After a markNodeCompleted, nodes[nodeId] is always populated. Defensive fallback.
                return JsonbPatchBuilder.Result.fallback();
            }
            patches.add(new JsonbPatch(
                    PatchPaths.nodeCounts(nodeId),
                    stateSnapshotMapper.writeValueAsString(counts)));
            return JsonbPatchBuilder.Result.patch(patches);
        } catch (JsonProcessingException e) {
            log.warn("[MarkNodeCompletedPatchBuilder] Jackson serialization failed for runId-trigger-epoch={}-{}-{}, falling back to full rewrite",
                    triggerId, epoch, nodeId, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
