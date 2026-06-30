package com.apimarketplace.orchestrator.repository;

/**
 * Combined read-only projection of {@code (state_snapshot_seq, state_snapshot)}
 * - a single round-trip on the unique index, used by the 3-tier read path
 * (audit Opus v3 - B M4 / C6).
 *
 * <p>Replaces the two separate calls
 * {@link WorkflowRunRepository#findStateSnapshotSeqByRunIdPublic(String)} +
 * {@link WorkflowRunRepository#findStateSnapshotByRunIdPublic(String)}
 * on the L2 fall-through path: instead of paying 2× network RTT + 2× index
 * lookup, one statement returns both columns. Eliminates the TOCTOU window
 * where the seq read could observe a stale value before the snapshot read.
 *
 * <p>The seq-only projection is still the right call when only the cache
 * oracle is needed (e.g. {@link com.apimarketplace.orchestrator.services.state.StateSnapshotJsonCache}
 * pre-check before HGET) - that path doesn't need the JSONB.
 *
 * <p>See {@code .claude_full_optim_plan_v3.md §4} (3-tier read).
 */
public interface StateSnapshotSeqAndJsonProjection {

    Long getStateSnapshotSeq();

    String getStateSnapshot();
}
