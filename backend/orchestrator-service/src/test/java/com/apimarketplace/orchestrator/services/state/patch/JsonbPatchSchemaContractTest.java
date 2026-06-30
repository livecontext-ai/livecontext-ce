package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-evolution contract: every {@link Set}-typed field in {@link EpochState}
 * MUST have a corresponding constant in {@link PatchPaths}.
 *
 * <p>Why this matters: when a contributor adds a new field to {@code EpochState}
 * (e.g. a new {@code Set<String>} for a new node-status category), the
 * full-rewrite path picks it up automatically via Jackson, but the patch
 * path will silently NOT touch it on the wired hot mutators - causing a
 * read-after-patch divergence between the two paths.
 *
 * <p>The fix when this test fails is one of:
 * <ol>
 *   <li>Add a path constant to {@link PatchPaths} for the new field.</li>
 *   <li>Update each builder that should write the new field, or have it
 *       fall back to full rewrite when the field is non-empty.</li>
 *   <li>If the field is intentionally orthogonal to all 3 wired mutators,
 *       add it to the allow-list below with a justification.</li>
 * </ol>
 */
class JsonbPatchSchemaContractTest {

    /** Set fields known to be unrelated to the 3 wired patch mutators. */
    private static final Set<String> ALLOWED_UNCOVERED_SET_FIELDS = Set.of(
            // None today - any future Set-typed EpochState field must be considered.
    );

    @Test
    @DisplayName("Every Set-typed EpochState field has a PatchPaths constant")
    void everySetFieldHasPathConstant() {
        Set<String> setFields = new HashSet<>();
        for (Field f : EpochState.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Set.class.isAssignableFrom(f.getType())) {
                setFields.add(f.getName());
            }
        }
        // Sanity: we expect at least the 6 known sets from EpochState (completed,
        // failed, partialFailed, skipped, running, ready, awaitingSignal).
        assertThat(setFields).contains(
                "completedNodeIds", "failedNodeIds", "partialFailedNodeIds",
                "skippedNodeIds", "runningNodeIds", "readyNodeIds", "awaitingSignalNodeIds");

        Set<String> declared = Set.of(
                PatchPaths.COMPLETED_NODE_IDS,
                PatchPaths.FAILED_NODE_IDS,
                PatchPaths.PARTIAL_FAILED_NODE_IDS,
                PatchPaths.SKIPPED_NODE_IDS,
                PatchPaths.RUNNING_NODE_IDS,
                PatchPaths.READY_NODE_IDS,
                PatchPaths.AWAITING_SIGNAL_NODE_IDS
        );

        Set<String> missing = new HashSet<>(setFields);
        missing.removeAll(declared);
        missing.removeAll(ALLOWED_UNCOVERED_SET_FIELDS);

        assertThat(missing)
                .as("New Set fields detected on EpochState without a corresponding "
                        + "PatchPaths constant. Add one (or document the exemption in "
                        + "ALLOWED_UNCOVERED_SET_FIELDS with a justification). Detected: "
                        + Arrays.toString(missing.toArray()))
                .isEmpty();
    }

    /**
     * Map-typed fields on {@link EpochState} explicitly NOT covered by any
     * round-1 patch builder. Each entry must come with a justification in
     * Javadoc - adding a new Map field without an entry here makes
     * {@link #everyMapFieldIsAcknowledged} fail, forcing the contributor to
     * decide: "patch path covers this" or "intentionally orthogonal, document why".
     */
    /**
     * Map fields acknowledged in this list are either:
     * (a) covered by a patch builder (path documented in the comment), or
     * (b) intentionally NOT covered with a justification (rare/multi-path mutator).
     *
     * <p>The test {@link #everyMapFieldIsAcknowledged} asserts every Map field
     * on {@link EpochState} is present here - adding a new field forces a
     * conscious decision (cover or document why not).
     */
    private static final Set<String> ACKNOWLEDGED_UNCOVERED_MAP_FIELDS = Set.of(
            // COVERED by RecordDecisionBranchPatchBuilder (R5). Patches the whole
            // Map at dags.{trigger}.epochs.{epoch}.decisionBranches - whole-Map
            // replacement avoids the jsonb_set create_missing path-init edge case
            // for first-decision-on-new-nodeId.
            "decisionBranches",
            // NOT covered: loop iteration state. Mutators are rare (1 per loop
            // body iteration) and touch multiple sub-fields per iter - full
            // rewrite is competitive.
            "loops",
            // NOT covered: split per-item state. Touched by split-async barrier
            // mutators that already use markNodeCompletedEpochOnly /
            // markNodePartialFailure / incrementNodeCountsOnly (all wired); the
            // splits Map itself mutates rarely (1 per split lifecycle).
            "splits"
    );

    @Test
    @DisplayName("Every Map-typed EpochState field is either covered or acknowledged-uncovered")
    void everyMapFieldIsAcknowledged() {
        Set<String> mapFields = new HashSet<>();
        for (Field f : EpochState.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Map.class.isAssignableFrom(f.getType())) {
                mapFields.add(f.getName());
            }
        }
        // Sanity: the 3 known maps from EpochState
        assertThat(mapFields).contains("decisionBranches", "loops", "splits");

        Set<String> unacknowledged = new HashSet<>(mapFields);
        unacknowledged.removeAll(ACKNOWLEDGED_UNCOVERED_MAP_FIELDS);

        assertThat(unacknowledged)
                .as("New Map fields detected on EpochState. Either add a patch builder "
                        + "covering them, or add to ACKNOWLEDGED_UNCOVERED_MAP_FIELDS with "
                        + "a justification. Detected: " + Arrays.toString(unacknowledged.toArray()))
                .isEmpty();
    }

    @Test
    @DisplayName("Every PatchPaths set-name constant maps to a real EpochState field")
    void everyPathConstantBacksRealField() {
        Set<String> allFields = new HashSet<>();
        for (Field f : EpochState.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) allFields.add(f.getName());
        }
        Set<String> declared = Set.of(
                PatchPaths.COMPLETED_NODE_IDS,
                PatchPaths.FAILED_NODE_IDS,
                PatchPaths.PARTIAL_FAILED_NODE_IDS,
                PatchPaths.SKIPPED_NODE_IDS,
                PatchPaths.RUNNING_NODE_IDS,
                PatchPaths.READY_NODE_IDS,
                PatchPaths.AWAITING_SIGNAL_NODE_IDS
        );
        Set<String> orphan = new HashSet<>(declared);
        orphan.removeAll(allFields);
        assertThat(orphan)
                .as("PatchPaths references field names that no longer exist on EpochState. "
                        + "Either restore the field or remove the constant.")
                .isEmpty();
    }
}
