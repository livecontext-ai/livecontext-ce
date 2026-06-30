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
import java.util.Set;

/**
 * Patch builder for {@code recordDecisionBranch(runId, triggerId, epoch, nodeId, branch)}
 * (epoch-scoped). Decision branches are stored as a {@code Map<String, Set<String>>}
 * inside {@link EpochState}: {@code decisionBranches[nodeId] = {branch1, branch2, ...}}.
 *
 * <p>Mutator semantics (StateSnapshot.java:373 + EpochState.java:242): pure
 * set-add inside a map entry - {@code decisionBranches.computeIfAbsent(nodeId,
 * HashSet::new).add(branch)}. No NodeCounts touch, no other set mutation.
 *
 * <p>The patch replaces the ENTIRE {@code decisionBranches} map at
 * {@code dags.{triggerId}.epochs.{epoch}.decisionBranches} rather than
 * targeting a single nodeId entry. Rationale: jsonb_set with create_missing=true
 * does NOT create intermediate object keys, only the LAST. So patching
 * {@code …decisionBranches.<nodeId>} fails silently when the parent map
 * has never been written (first-decision case). Replacing the whole map is
 * marginally more bytes but avoids the path-init edge case entirely.
 *
 * <p><b>NO_OP</b> when the {@code branch} string is already present in
 * {@code decisionBranches[nodeId]} - pure idempotent.
 *
 * <p><b>FALLBACK</b> when the epoch is not materialized in {@code before}
 * (path-init prerequisite for the parent path).
 *
 * <p><b>Élide</b> - not applicable: this builder doesn't write {@code runningNodeIds}.
 */
@Component
@PatchClass(opKind = PatchClass.OpKind.ASSIGN)
public class RecordDecisionBranchPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(RecordDecisionBranchPatchBuilder.class);

    private final ObjectMapper stateSnapshotMapper;

    public RecordDecisionBranchPatchBuilder(@Qualifier("stateSnapshotMapper") ObjectMapper stateSnapshotMapper) {
        this.stateSnapshotMapper = stateSnapshotMapper;
    }

    public JsonbPatchBuilder.Result build(StateSnapshot before, StateSnapshot after,
                                          String triggerId, int epoch, String nodeId, String branch) {
        DagState beforeDag = before.getDags().get(triggerId);
        if (beforeDag == null || beforeDag.getEpochState(epoch) == null) {
            return JsonbPatchBuilder.Result.fallback();
        }
        EpochState beforeEpoch = beforeDag.getEpochState(epoch);
        Set<String> existingBranches = beforeEpoch.getDecisionBranchesMap().get(nodeId);
        if (existingBranches != null && existingBranches.contains(branch)) {
            return JsonbPatchBuilder.Result.noOp();
        }
        DagState afterDag = after.getDags().get(triggerId);
        EpochState afterEpoch = afterDag != null ? afterDag.getEpochState(epoch) : null;
        if (afterEpoch == null) return JsonbPatchBuilder.Result.fallback();
        try {
            return JsonbPatchBuilder.Result.patch(List.of(
                    new JsonbPatch(PatchPaths.seq(), Long.toString(after.getSeq())),
                    new JsonbPatch(
                            new String[] { "dags", triggerId, "epochs",
                                    Integer.toString(epoch), "decisionBranches" },
                            stateSnapshotMapper.writeValueAsString(afterEpoch.getDecisionBranchesMap()))
            ));
        } catch (JsonProcessingException e) {
            log.warn("[RecordDecisionBranchPatchBuilder] Jackson failed for {}-{}-{}/{}, fallback",
                    triggerId, epoch, nodeId, branch, e);
            return JsonbPatchBuilder.Result.fallback();
        }
    }
}
