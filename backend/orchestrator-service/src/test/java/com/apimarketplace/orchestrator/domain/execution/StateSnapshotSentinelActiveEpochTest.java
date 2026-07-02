package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sentinel-aware semantics of {@link StateSnapshot#hasAnyActiveEpoch()}.
 *
 * <p>Regression for the rerun trigger-cycle poisoning: an epoch that leaks onto the
 * {@code "trigger:default"} V2-migration sentinel can never be closed (the sentinel
 * never receives trigger fires), so counting it pinned {@code hasAnyActiveEpoch} to
 * true forever and {@code resetForNextCycle} could never re-arm the run to
 * WAITING_TRIGGER - the zombie scanner then failed the armed run after the
 * no-progress threshold. Legacy V2-migrated runs whose ONLY DAG is the sentinel
 * must keep their active epochs counted.
 */
@DisplayName("StateSnapshot.hasAnyActiveEpoch sentinel semantics")
class StateSnapshotSentinelActiveEpochTest {

    private static EpochState emptyEpoch() {
        return new EpochState(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Map.of(), Map.of(), Map.of(), Instant.now());
    }

    private static DagState dagWithActiveEpoch(int epoch) {
        return new DagState(epoch, 0, 1, Map.of(epoch, emptyEpoch()), Set.of(epoch));
    }

    private static DagState dagWithNoActiveEpoch(int epoch) {
        return new DagState(epoch, 0, 1, Map.of(epoch, emptyEpoch()), Set.of());
    }

    private static StateSnapshot snapshotOf(Map<String, DagState> dags) {
        return new StateSnapshot(3, 0L, dags,
            null, null, null, null, null, Map.of(), Map.of(),
            null, null, null, null, 0L, Instant.now());
    }

    @Test
    @DisplayName("An active epoch stranded on the trigger:default sentinel is ignored when a real DAG exists")
    void sentinelActiveEpochIgnoredWhenRealDagExists() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithNoActiveEpoch(3),
            "trigger:default", dagWithActiveEpoch(0)));

        assertFalse(snapshot.hasAnyActiveEpoch(),
            "a sentinel-only active epoch must not wedge the run: no trigger fire can ever close it");
    }

    @Test
    @DisplayName("A real DAG's active epoch still counts even when the sentinel is present")
    void realDagActiveEpochStillCounts() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithActiveEpoch(3),
            "trigger:default", dagWithActiveEpoch(0)));

        assertTrue(snapshot.hasAnyActiveEpoch());
    }

    @Test
    @DisplayName("A legacy V2-migrated run whose only DAG is the sentinel keeps its active epochs counted")
    void sentinelOnlyRunKeepsActiveEpochsCounted() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:default", dagWithActiveEpoch(0)));

        assertTrue(snapshot.hasAnyActiveEpoch(),
            "legacy single-sentinel runs still rely on the sentinel's active epochs");
    }

    @Test
    @DisplayName("No active epochs anywhere reports false")
    void noActiveEpochsAnywhere() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithNoActiveEpoch(2)));

        assertFalse(snapshot.hasAnyActiveEpoch());
    }

    @Test
    @DisplayName("resetDag(Set) does not reactivate the dormant sentinel when a real DAG exists (rerun path)")
    void resetDagDoesNotReactivateSentinelWhenRealDagExists() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithNoActiveEpoch(2),
            "trigger:default", dagWithNoActiveEpoch(0)));

        StateSnapshot afterRerunReset = snapshot.resetDag(Set.of("core:stamp"));

        assertTrue(afterRerunReset.getDags().get("trigger:start").hasActiveEpochs(),
            "the real DAG must be reactivated so the rerun's flat view sees its epoch data");
        assertFalse(afterRerunReset.getDags().get("trigger:default").hasActiveEpochs(),
            "the sentinel must stay dormant: no trigger fire can ever close a sentinel epoch");
    }

    @Test
    @DisplayName("resetDag(Set) heals sentinel epochs already leaked by earlier reruns")
    void resetDagHealsAlreadyLeakedSentinelEpochs() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithNoActiveEpoch(2),
            "trigger:default", dagWithActiveEpoch(0)));

        StateSnapshot afterRerunReset = snapshot.resetDag(Set.of("core:stamp"));

        assertFalse(afterRerunReset.getDags().get("trigger:default").hasActiveEpochs(),
            "an active sentinel epoch left by a pre-fix rerun must be closed, not carried forever");
    }

    @Test
    @DisplayName("Owner-scoped resetDag reactivates ONLY the owner DAG, never a sibling (multi-trigger rerun)")
    void ownerScopedResetDagLeavesSiblingsDormant() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:start", dagWithNoActiveEpoch(2),
            "trigger:side", dagWithNoActiveEpoch(3)));

        StateSnapshot afterRerunReset = snapshot.resetDag(Set.of("core:stamp"), "trigger:start");

        assertTrue(afterRerunReset.getDags().get("trigger:start").hasActiveEpochs(),
            "the owner DAG must be reactivated for the rerun");
        assertFalse(afterRerunReset.getDags().get("trigger:side").hasActiveEpochs(),
            "a sibling epoch reactivated by a rerun would never be closed by any cycle");
    }

    @Test
    @DisplayName("resetDag(Set) still reactivates the sentinel for a legacy sentinel-only run")
    void resetDagStillReactivatesSentinelForLegacyRun() {
        StateSnapshot snapshot = snapshotOf(Map.of(
            "trigger:default", dagWithNoActiveEpoch(0)));

        StateSnapshot afterRerunReset = snapshot.resetDag(Set.of("core:stamp"));

        assertTrue(afterRerunReset.getDags().get("trigger:default").hasActiveEpochs(),
            "a legacy V2-migrated run has only the sentinel: the rerun reset must keep working on it");
    }

    @Test
    @DisplayName("Owner-scoped resetDag on a sentinel-only legacy run strips the reset nodes without reactivating the sentinel")
    void ownerScopedResetDagOnSentinelOnlyRunStripsWithoutReactivation() {
        // The REAL rerun path always passes a plan-derived owner (e.g. trigger:start),
        // never null - so on a legacy run the sentinel takes the non-owner branch:
        // stripped, dormant, and its surviving data stays readable through the
        // no-active-epochs fallback of the flat views.
        EpochState legacyEpoch = new EpochState(
            Set.of("core:stamp", "core:keep"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Map.of(), Map.of(), Map.of(), Instant.now());
        DagState sentinel = new DagState(0, 0, 1, Map.of(0, legacyEpoch), Set.of());
        StateSnapshot snapshot = snapshotOf(Map.of("trigger:default", sentinel));

        StateSnapshot afterRerunReset = snapshot.resetDag(Set.of("core:stamp"), "trigger:start");

        DagState after = afterRerunReset.getDags().get("trigger:default");
        assertFalse(after.hasActiveEpochs(),
            "the sentinel must stay dormant on the owner-scoped rerun path");
        assertFalse(after.currentEpochState().getCompletedNodeIds().contains("core:stamp"),
            "the reset node must be stripped from the sentinel's dormant epoch");
        assertTrue(afterRerunReset.getCompletedNodeIds().contains("core:keep"),
            "surviving legacy data must stay readable through the dormant-epoch fallback");
    }
}
