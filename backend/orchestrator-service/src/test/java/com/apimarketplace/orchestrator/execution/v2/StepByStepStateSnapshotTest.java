package com.apimarketplace.orchestrator.execution.v2;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for Step-by-Step (SBS) execution state management.
 *
 * <p>These tests verify the exact state transitions that drive the frontend buttons:
 * <ul>
 *   <li><b>READY</b> (play button): node is in readyNodeIds, canExecute=true, status=PENDING</li>
 *   <li><b>RERUN</b> (rerun button): node is in readyNodeIds AND has NodeCounts.completed > 0</li>
 *   <li><b>COMPLETED</b>: node in completedNodeIds (no button)</li>
 *   <li><b>RUNNING</b>: node in runningNodeIds (spinner)</li>
 *   <li><b>PENDING</b> (inactive): not in any set (grayed, no button)</li>
 * </ul>
 *
 * <p>Workflow topology used in most tests:
 * <pre>
 *   trigger:webhook → mcp:step_a → mcp:step_b
 * </pre>
 *
 * <p>Multi-DAG topology (two triggers, independent DAGs):
 * <pre>
 *   trigger:webhook → mcp:step_a → mcp:step_b
 *   trigger:manual  → mcp:step_c → mcp:step_d
 * </pre>
 */
@DisplayName("Step-by-Step State Snapshot Tests")
class StepByStepStateSnapshotTest {

    // ========================================================================
    // Constants
    // ========================================================================

    private static final String TRIGGER = "trigger:webhook";
    private static final String STEP_A = "mcp:step_a";
    private static final String STEP_B = "mcp:step_b";

    private static final String TRIGGER_2 = "trigger:manual";
    private static final String STEP_C = "mcp:step_c";
    private static final String STEP_D = "mcp:step_d";

    // ========================================================================
    // Single DAG - Basic SBS Flow
    // ========================================================================

    @Nested
    @DisplayName("Single DAG - Basic SBS Flow")
    class SingleDagBasicFlow {

        @Test
        @DisplayName("Step 0: Initialization - trigger is READY, children are PENDING (inactive)")
        void initialization_triggerReady_childrenPending() {
            // SBS starts: trigger is the only ready node
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    epochWithReady(Set.of(TRIGGER)), 1, true);

            // Trigger: READY (play button)
            assertReady(snapshot, TRIGGER);

            // Children: PENDING (inactive, no button)
            assertPending(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);
        }

        @Test
        @DisplayName("Step 1: Execute trigger - trigger COMPLETED, children become READY")
        void executeTrigger_triggerCompleted_childrenReady() {
            // User clicks trigger → trigger completes, step_a becomes ready
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER),          // completed
                            Set.of(),                 // failed
                            Set.of(),                 // skipped
                            Set.of(),                 // running
                            Set.of(STEP_A),           // ready (child of trigger)
                            Set.of(),                 // awaiting
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            // Trigger: COMPLETED (with NodeCounts)
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            // Rebuild flat views
            snapshot = rebuildSnapshot(snapshot, TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_A), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertCompleted(snapshot, TRIGGER);
            assertReady(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);
        }

        @Test
        @DisplayName("Step 2: Execute step_a - step_a COMPLETED, step_b becomes READY")
        void executeStepA_stepACompleted_stepBReady() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A),  // completed
                            Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_B),           // ready
                            Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);

            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertReady(snapshot, STEP_B);
        }

        @Test
        @DisplayName("Step 3: Execute step_b - all COMPLETED, epoch closes")
        void executeStepB_allCompleted_epochCloses() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(),                 // no ready nodes
                            Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertCompleted(snapshot, STEP_B);
            assertThat(snapshot.getReadyNodeIds()).isEmpty();
        }
    }

    // ========================================================================
    // Single DAG - Rerun Scenarios
    // ========================================================================

    @Nested
    @DisplayName("Single DAG - Rerun Trigger (new epoch)")
    class SingleDagRerunTrigger {

        @Test
        @DisplayName("Rerun trigger: all children lose their buttons, trigger becomes READY")
        void rerunTrigger_childrenReset_triggerReady() {
            // After full execution, user clicks "rerun" on trigger
            // Previous epoch closed. New epoch opened with trigger ready.
            // NodeCounts still has completed=1 from previous run.

            // Simulate: epoch 2 with trigger ready, epoch 1 was closed
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    epochWithReady(Set.of(TRIGGER)), 2, true);
            // NodeCounts from previous epoch
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            // Trigger: READY (in readyNodeIds), despite NodeCounts.completed=1 → shows as RERUN button
            assertReady(snapshot, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(1);

            // Children: PENDING (not in any set in the new epoch)
            assertPending(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);
        }

        @Test
        @DisplayName("After rerun trigger executes: trigger COMPLETED (rerun badge), children READY")
        void afterRerunTriggerExecutes_triggerRerunCompleted_childrenReady() {
            // Trigger executed in epoch 2 after rerun
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_A), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 2, true);
            // NodeCounts: completed=2 (once per epoch)
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, TRIGGER);

            assertCompleted(snapshot, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(2);
            assertReady(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);
        }
    }

    @Nested
    @DisplayName("Single DAG - Rerun Node (selective reset, same epoch)")
    class SingleDagRerunNode {

        @Test
        @DisplayName("Rerun step_a: step_a becomes READY, step_b loses button, trigger stays COMPLETED")
        void rerunStepA_stepAReady_stepBReset_triggerStays() {
            // Full execution completed in epoch 1.
            // User clicks rerun on step_a.
            // resetDag(Set.of("mcp:step_a", "mcp:step_b")) removes step_a + downstream from epoch state.
            // Trigger stays COMPLETED because it's not in the reset set.

            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            // Rerun step_a: selective reset of step_a + step_b
            snapshot = snapshot.resetDag(Set.of(STEP_A, STEP_B));

            // Trigger stays COMPLETED (not in reset set)
            assertCompleted(snapshot, TRIGGER);

            // step_a: removed from completedNodeIds → PENDING in flat view
            // It should be made READY next by ReadyNodeCalculator (not tested here, just the snapshot state)
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_B);

            // NodeCounts still preserved (completed=1 from before)
            assertThat(snapshot.getNodes().get(STEP_A).completed()).isEqualTo(1);
            assertThat(snapshot.getNodes().get(STEP_B).completed()).isEqualTo(1);
        }

        @Test
        @DisplayName("After selective reset, adding step_a to readyNodeIds: step_a READY, step_b PENDING")
        void afterSelectiveReset_stepAReady_stepBPending() {
            // After resetDag, ReadyNodeCalculator would determine step_a is ready.
            // We simulate the final state after ready nodes are set.

            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER),          // trigger stays completed
                            Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_A),           // step_a is now ready
                            Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);  // previous run
            snapshot = withNodeCompleted(snapshot, STEP_B);  // previous run

            // step_a: READY in flat view + has NodeCounts.completed=1 → frontend shows RERUN button
            assertReady(snapshot, STEP_A);
            assertThat(snapshot.getNodes().get(STEP_A).completed()).isEqualTo(1);

            // step_b: NOT ready, NOT completed → PENDING (inactive, no button)
            assertPending(snapshot, STEP_B);

            // Trigger: COMPLETED (unchanged)
            assertCompleted(snapshot, TRIGGER);
        }

        @Test
        @DisplayName("Rerun step_a at same level as step_b (fork): step_a READY, step_b unchanged READY")
        void rerunStepA_siblingStepBUnchanged() {
            // Topology: trigger → fork → [step_a, step_b] (parallel)
            // Both completed. Rerun step_a only.
            // step_b is NOT downstream of step_a, so it stays completed.

            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            // Rerun only step_a (step_b is a sibling, not downstream)
            snapshot = snapshot.resetDag(Set.of(STEP_A));

            // step_a removed from completed
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);

            // step_b stays completed (not in reset set)
            assertCompleted(snapshot, STEP_B);
            assertCompleted(snapshot, TRIGGER);
        }
    }

    // ========================================================================
    // Single DAG - Running State
    // ========================================================================

    @Nested
    @DisplayName("Single DAG - Running Node State")
    class SingleDagRunningState {

        @Test
        @DisplayName("Node moves from READY to RUNNING when execution starts")
        void nodeBecomesRunning() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(), Set.of(),
                            Set.of(STEP_A),           // running
                            Set.of(),                 // no longer ready
                            Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertThat(snapshot.getRunningNodeIds()).contains(STEP_A);
            assertThat(snapshot.getReadyNodeIds()).doesNotContain(STEP_A);
        }

        @Test
        @DisplayName("Node moves from RUNNING to COMPLETED after execution")
        void nodeCompletesAfterRunning() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A), Set.of(), Set.of(),
                            Set.of(),                 // not running anymore
                            Set.of(STEP_B),           // successor ready
                            Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertThat(snapshot.getRunningNodeIds()).doesNotContain(STEP_A);
            assertCompleted(snapshot, STEP_A);
            assertReady(snapshot, STEP_B);
        }
    }

    // ========================================================================
    // Single DAG - Epoch Lifecycle
    // ========================================================================

    @Nested
    @DisplayName("Single DAG - Epoch Lifecycle")
    class SingleDagEpochLifecycle {

        @Test
        @DisplayName("New epoch creates fresh EpochState, preserves NodeCounts")
        void newEpochFreshState_preservesNodeCounts() {
            // Epoch 1: all completed
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            // Advance to epoch 2 (resetDag)
            snapshot = snapshot.resetDag(TRIGGER, 2);

            // New epoch has fresh state
            EpochState epoch2 = snapshot.getDagState(TRIGGER).getEpochState(2);
            assertThat(epoch2.getCompletedNodeIds()).isEmpty();
            assertThat(epoch2.getFailedNodeIds()).isEmpty();

            // NodeCounts preserved
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(1);
            assertThat(snapshot.getNodes().get(STEP_A).completed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Closed epoch excluded from flat views")
        void closedEpochExcludedFromFlatViews() {
            // Epoch 1: completed, then closed
            DagState dag = DagState.initial()
                    .advanceEpoch(1);
            EpochState es = dag.currentEpochState()
                    .markNodeCompleted(TRIGGER)
                    .markNodeCompleted(STEP_A);
            dag = dag.withCurrentEpochState(es);
            // Close epoch 1
            dag = dag.closeAndPruneEpoch(1);

            StateSnapshot snapshot = createSnapshot(Map.of(TRIGGER, dag), null, null);

            // Flat views should be empty (epoch 1 is closed)
            assertThat(snapshot.getCompletedNodeIds()).isEmpty();
            assertThat(dag.getActiveEpochs()).doesNotContain(1);
        }

        @Test
        @DisplayName("Spawn increments on rerun within same epoch")
        void spawnIncrementsOnRerun() {
            DagState dag = DagState.initial().advanceEpoch(1);
            assertThat(dag.getCurrentSpawn()).isEqualTo(0);

            dag = dag.advanceSpawn();
            assertThat(dag.getCurrentSpawn()).isEqualTo(1);

            dag = dag.advanceSpawn();
            assertThat(dag.getCurrentSpawn()).isEqualTo(2);

            // Epoch stays the same
            assertThat(dag.getCurrentEpoch()).isEqualTo(1);
        }

        @Test
        @DisplayName("prepareDagForNextCycle creates epoch without activating it")
        void prepareDagForNextCycle_notActive() {
            StateSnapshot snapshot = StateSnapshot.empty();
            snapshot = snapshot.prepareDagForNextCycle(TRIGGER, 2, TRIGGER);

            DagState dag = snapshot.getDagState(TRIGGER);
            // Epoch 2 exists with trigger as ready
            assertThat(dag.getEpochState(2)).isNotNull();
            assertThat(dag.getEpochState(2).getReadyNodeIds()).contains(TRIGGER);
            // But NOT in activeEpochs (dormant)
            assertThat(dag.getActiveEpochs()).doesNotContain(2);

            // Flat view shows trigger as ready (because computeFlatSet falls back to currentEpochState)
            assertThat(snapshot.getReadyNodeIds()).contains(TRIGGER);
        }

        @Test
        @DisplayName("openEpochForDag activates a dormant epoch")
        void openEpochForDag_activates() {
            StateSnapshot snapshot = StateSnapshot.empty();
            snapshot = snapshot.prepareDagForNextCycle(TRIGGER, 2, TRIGGER);
            snapshot = snapshot.openEpochForDag(TRIGGER, 2);

            DagState dag = snapshot.getDagState(TRIGGER);
            assertThat(dag.getActiveEpochs()).contains(2);
        }
    }

    // ========================================================================
    // Multi-DAG - Independent DAG State
    // ========================================================================

    @Nested
    @DisplayName("Multi-DAG - Independent DAG State")
    class MultiDagIndependentState {

        @Test
        @DisplayName("Each DAG has its own epoch state, independent of the other")
        void eachDagHasOwnState() {
            // DAG 1: trigger:webhook → step_a → step_b (epoch 1, trigger completed)
            // DAG 2: trigger:manual → step_c → step_d (epoch 1, all pending)
            EpochState dag1Epoch = new EpochState(
                    Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                    Set.of(STEP_A), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            EpochState dag2Epoch = epochWithReady(Set.of(TRIGGER_2));

            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, dag1Epoch, 1,
                    TRIGGER_2, dag2Epoch, 1
            );

            // DAG 1: trigger completed, step_a ready
            assertCompleted(snapshot, TRIGGER);
            assertReady(snapshot, STEP_A);

            // DAG 2: trigger ready, step_c pending
            assertReady(snapshot, TRIGGER_2);
            assertPending(snapshot, STEP_C);
        }

        @Test
        @DisplayName("Executing node in DAG 1 does not affect DAG 2")
        void executingInDag1_doesNotAffectDag2() {
            // DAG 1: trigger + step_a completed, step_b ready
            // DAG 2: trigger ready
            EpochState dag1Epoch = new EpochState(
                    Set.of(TRIGGER, STEP_A), Set.of(), Set.of(), Set.of(),
                    Set.of(STEP_B), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            EpochState dag2Epoch = epochWithReady(Set.of(TRIGGER_2));

            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, dag1Epoch, 1,
                    TRIGGER_2, dag2Epoch, 1
            );

            // DAG 1 advanced
            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertReady(snapshot, STEP_B);

            // DAG 2 unchanged
            assertReady(snapshot, TRIGGER_2);
            assertPending(snapshot, STEP_C);
            assertPending(snapshot, STEP_D);
        }

        @Test
        @DisplayName("Both DAGs can have READY nodes simultaneously")
        void bothDagsCanHaveReadyNodes() {
            EpochState dag1Epoch = new EpochState(
                    Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                    Set.of(STEP_A), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            EpochState dag2Epoch = new EpochState(
                    Set.of(TRIGGER_2), Set.of(), Set.of(), Set.of(),
                    Set.of(STEP_C), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, dag1Epoch, 1,
                    TRIGGER_2, dag2Epoch, 1
            );

            // Both step_a and step_c are ready (from different DAGs)
            assertReady(snapshot, STEP_A);
            assertReady(snapshot, STEP_C);
        }

        @Test
        @DisplayName("Rerun trigger in DAG 1 does not reset DAG 2")
        void rerunTriggerInDag1_doesNotResetDag2() {
            // Both DAGs fully completed
            EpochState dag1Epoch = new EpochState(
                    Set.of(TRIGGER, STEP_A, STEP_B),
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            EpochState dag2Epoch = new EpochState(
                    Set.of(TRIGGER_2, STEP_C, STEP_D),
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, dag1Epoch, 1,
                    TRIGGER_2, dag2Epoch, 1
            );

            // Rerun DAG 1's trigger (new epoch for DAG 1 only)
            snapshot = snapshot.resetDag(TRIGGER, 2);

            // DAG 1: fresh epoch 2, all nodes reset
            EpochState dag1NewEpoch = snapshot.getDagState(TRIGGER).getEpochState(2);
            assertThat(dag1NewEpoch.getCompletedNodeIds()).isEmpty();

            // DAG 2: untouched, still completed
            EpochState dag2State = snapshot.getDagState(TRIGGER_2).currentEpochState();
            assertThat(dag2State.getCompletedNodeIds()).containsExactlyInAnyOrder(TRIGGER_2, STEP_C, STEP_D);
        }
    }

    // ========================================================================
    // Multi-DAG - Epoch Management
    // ========================================================================

    @Nested
    @DisplayName("Multi-DAG - Epoch Management")
    class MultiDagEpochManagement {

        @Test
        @DisplayName("DAGs can be at different epochs")
        void dagsAtDifferentEpochs() {
            // DAG 1: epoch 3 (fired 3 times)
            // DAG 2: epoch 1 (fired once)
            DagState dag1 = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2)
                    .advanceEpoch(3);
            DagState dag2 = DagState.initial()
                    .advanceEpoch(1);

            assertThat(dag1.getCurrentEpoch()).isEqualTo(3);
            assertThat(dag1.getFireCount()).isEqualTo(3);
            assertThat(dag2.getCurrentEpoch()).isEqualTo(1);
            assertThat(dag2.getFireCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Closing DAG 1 epoch does not close DAG 2 epoch")
        void closingDag1Epoch_doesNotCloseDag2() {
            DagState dag1 = DagState.initial().advanceEpoch(1);
            DagState dag2 = DagState.initial().advanceEpoch(1);

            // Close DAG 1's epoch 1
            dag1 = dag1.closeAndPruneEpoch(1);

            assertThat(dag1.getActiveEpochs()).isEmpty();
            assertThat(dag2.getActiveEpochs()).contains(1);
        }

        @Test
        @DisplayName("Selective reset in multi-DAG only affects the containing DAG")
        void selectiveResetInMultiDag_onlyAffectsContainingDag() {
            // DAG 1: trigger + step_a completed
            // DAG 2: trigger_2 + step_c completed
            EpochState dag1Epoch = new EpochState(
                    Set.of(TRIGGER, STEP_A), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            EpochState dag2Epoch = new EpochState(
                    Set.of(TRIGGER_2, STEP_C), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, dag1Epoch, 1,
                    TRIGGER_2, dag2Epoch, 1
            );

            // Selective reset: rerun step_a only (DAG 1)
            snapshot = snapshot.resetDag(Set.of(STEP_A));

            // DAG 1: step_a removed, trigger stays
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);
            assertCompleted(snapshot, TRIGGER);

            // DAG 2: completely untouched
            assertCompleted(snapshot, TRIGGER_2);
            assertCompleted(snapshot, STEP_C);
        }
    }

    // ========================================================================
    // EpochState - Selective Node Removal
    // ========================================================================

    @Nested
    @DisplayName("EpochState - Selective Node Removal")
    class EpochStateSelectiveRemoval {

        @Test
        @DisplayName("removeNodes removes target and downstream from all tracking sets")
        void removeNodes_removesFromAllSets() {
            EpochState state = new EpochState(
                    Set.of(TRIGGER, STEP_A, STEP_B),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            EpochState result = state.removeNodes(Set.of(STEP_A, STEP_B));

            assertThat(result.getCompletedNodeIds()).containsExactly(TRIGGER);
            assertThat(result.getCompletedNodeIds()).doesNotContain(STEP_A, STEP_B);
        }

        @Test
        @DisplayName("removeNodes removes from running and ready sets too")
        void removeNodes_removesFromRunningAndReady() {
            EpochState state = new EpochState(
                    Set.of(TRIGGER), Set.of(), Set.of(),
                    Set.of(STEP_A),            // running
                    Set.of(STEP_B),            // ready
                    Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            EpochState result = state.removeNodes(Set.of(STEP_A, STEP_B));

            assertThat(result.getRunningNodeIds()).isEmpty();
            assertThat(result.getReadyNodeIds()).isEmpty();
            assertThat(result.getCompletedNodeIds()).containsExactly(TRIGGER);
        }

        @Test
        @DisplayName("removeNodes removes decision branches for reset nodes")
        void removeNodes_removesDecisionBranches() {
            EpochState state = new EpochState(
                    Set.of(TRIGGER, "core:check"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of("core:check", Set.of("if")),
                    Map.of(), Map.of(), null
            );

            EpochState result = state.removeNodes(Set.of("core:check"));

            assertThat(result.getCompletedNodeIds()).containsExactly(TRIGGER);
            assertThat(result.getDecisionBranchesMap()).isEmpty();
        }

        @Test
        @DisplayName("removeNodes preserves nodes NOT in the reset set")
        void removeNodes_preservesOtherNodes() {
            EpochState state = new EpochState(
                    Set.of(TRIGGER, STEP_A, STEP_B),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );

            // Only remove step_b
            EpochState result = state.removeNodes(Set.of(STEP_B));

            assertThat(result.getCompletedNodeIds()).containsExactlyInAnyOrder(TRIGGER, STEP_A);
        }

        @Test
        @DisplayName("removeNodes clears stale LoopState for reset loop nodes (rerun restarts iteration 0)")
        void removeNodes_clearsLoopStateForResetNodes() {
            // Regression: rerun of (or through) a loop node left the pre-rerun LoopState
            // (iteration counter) in EpochState.loops - state reconstruction then saw the
            // loop as mid-iteration instead of fresh.
            StateSnapshot.LoopState midLoop = StateSnapshot.LoopState.initial(5).nextIteration();
            EpochState state = new EpochState(
                    Set.of(TRIGGER, "core:my_loop"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(),
                    Map.of("core:my_loop", midLoop),
                    Map.of(), null
            );

            EpochState result = state.removeNodes(Set.of("core:my_loop"));

            assertThat(result.getLoops()).isEmpty();
            assertThat(result.getCompletedNodeIds()).containsExactly(TRIGGER);
        }

        @Test
        @DisplayName("removeNodes clears stale SplitState for reset split nodes (rerun re-spawns items)")
        void removeNodes_clearsSplitStateForResetNodes() {
            // Regression: rerun of a split node left the pre-rerun SplitState (item
            // progress) in EpochState.splits - split-awareness checks
            // (V2StepByStepService.isSplitAwareNode) and reconstruction read stale progress.
            StateSnapshot.SplitState midSplit = StateSnapshot.SplitState.initial(3).incrementCompleted();
            EpochState state = new EpochState(
                    Set.of(TRIGGER, "core:my_split"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(),
                    Map.of("core:my_split", midSplit),
                    null
            );

            EpochState result = state.removeNodes(Set.of("core:my_split"));

            assertThat(result.getSplits()).isEmpty();
            assertThat(result.getCompletedNodeIds()).containsExactly(TRIGGER);
        }

        @Test
        @DisplayName("removeNodes preserves loop/split state of nodes NOT being reset")
        void removeNodes_preservesUnrelatedLoopAndSplitState() {
            StateSnapshot.LoopState otherLoop = StateSnapshot.LoopState.initial(2);
            StateSnapshot.SplitState otherSplit = StateSnapshot.SplitState.initial(4);
            EpochState state = new EpochState(
                    Set.of(TRIGGER, STEP_A, "core:other_loop", "core:other_split"),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(),
                    Map.of("core:other_loop", otherLoop),
                    Map.of("core:other_split", otherSplit),
                    null
            );

            EpochState result = state.removeNodes(Set.of(STEP_A));

            assertThat(result.getLoops()).containsEntry("core:other_loop", otherLoop);
            assertThat(result.getSplits()).containsEntry("core:other_split", otherSplit);
        }
    }

    // ========================================================================
    // DagState - Reactivation After Rerun
    // ========================================================================

    @Nested
    @DisplayName("DagState - Reactivation After Rerun")
    class DagStateReactivation {

        @Test
        @DisplayName("reactivateCurrentEpoch adds closed epoch back to activeEpochs")
        void reactivateCurrentEpoch_addsBackToActive() {
            DagState dag = DagState.initial().advanceEpoch(1);
            dag = dag.closeAndPruneEpoch(1);
            assertThat(dag.getActiveEpochs()).isEmpty();

            // Need to re-add epoch state since closeAndPrune removes it
            dag = dag.withCurrentEpochState(EpochState.fresh());
            dag = dag.reactivateCurrentEpoch();
            assertThat(dag.getActiveEpochs()).contains(1);
        }

        @Test
        @DisplayName("reactivateCurrentEpoch is idempotent when already active")
        void reactivateCurrentEpoch_idempotent() {
            DagState dag = DagState.initial().advanceEpoch(1);
            assertThat(dag.getActiveEpochs()).contains(1);

            DagState reactivated = dag.reactivateCurrentEpoch();
            assertThat(reactivated.getActiveEpochs()).contains(1);
            assertThat(reactivated).isSameAs(dag); // no-op returns same instance
        }
    }

    // ========================================================================
    // Full SBS Scenario - Execute, Rerun, Re-Execute
    // ========================================================================

    @Nested
    @DisplayName("Full SBS Scenario - Complete Lifecycle")
    class FullSbsLifecycle {

        @Test
        @DisplayName("Full lifecycle: init → execute all → rerun trigger → re-execute all")
        void fullLifecycle_initExecuteRerunReexecute() {
            // === Phase 1: Initialize ===
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    epochWithReady(Set.of(TRIGGER)), 1, true);
            assertReady(snapshot, TRIGGER);
            assertPending(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);

            // === Phase 2: Execute trigger ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER), Set.of(STEP_A));
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, TRIGGER);
            assertReady(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);

            // === Phase 3: Execute step_a ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER, STEP_A), Set.of(STEP_B));
            snapshot = withNodeCompleted(snapshot, STEP_A);
            assertCompleted(snapshot, STEP_A);
            assertReady(snapshot, STEP_B);

            // === Phase 4: Execute step_b ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER, STEP_A, STEP_B), Set.of());
            snapshot = withNodeCompleted(snapshot, STEP_B);
            assertCompleted(snapshot, STEP_B);
            assertThat(snapshot.getReadyNodeIds()).isEmpty();

            // === Phase 5: Rerun trigger (new epoch 2) ===
            snapshot = snapshot.resetDag(TRIGGER, 2);
            // In epoch 2, trigger is the first node
            snapshot = rebuildSnapshot(snapshot, TRIGGER,
                    epochWithReady(Set.of(TRIGGER)), 2, true);

            assertReady(snapshot, TRIGGER);
            assertPending(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);
            // NodeCounts still show previous results
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(1);
            assertThat(snapshot.getNodes().get(STEP_A).completed()).isEqualTo(1);
            assertThat(snapshot.getNodes().get(STEP_B).completed()).isEqualTo(1);

            // === Phase 6: Re-execute trigger in epoch 2 ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER), Set.of(STEP_A));
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(2);
            assertReady(snapshot, STEP_A);
        }

        @Test
        @DisplayName("Full lifecycle: execute → rerun middle node → re-execute downstream")
        void fullLifecycle_rerunMiddleNode() {
            // === Phase 1: Full execution ===
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            // === Phase 2: Rerun step_a (selective reset) ===
            snapshot = snapshot.resetDag(Set.of(STEP_A, STEP_B));

            // Trigger stays completed
            assertCompleted(snapshot, TRIGGER);
            // step_a and step_b removed from completed
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A, STEP_B);

            // Simulate ReadyNodeCalculator marking step_a as ready
            snapshot = snapshot.addReadyNode(TRIGGER, STEP_A);
            assertReady(snapshot, STEP_A);
            assertPending(snapshot, STEP_B);

            // === Phase 3: Re-execute step_a ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER, STEP_A), Set.of(STEP_B));
            snapshot = withNodeCompleted(snapshot, STEP_A);

            assertCompleted(snapshot, STEP_A);
            assertThat(snapshot.getNodes().get(STEP_A).completed()).isEqualTo(2);
            assertReady(snapshot, STEP_B);

            // === Phase 4: Re-execute step_b ===
            snapshot = transitionNode(snapshot, TRIGGER,
                    Set.of(TRIGGER, STEP_A, STEP_B), Set.of());
            snapshot = withNodeCompleted(snapshot, STEP_B);

            assertCompleted(snapshot, STEP_B);
            assertThat(snapshot.getNodes().get(STEP_B).completed()).isEqualTo(2);
            assertThat(snapshot.getReadyNodeIds()).isEmpty();
        }
    }

    // ========================================================================
    // Full Multi-DAG SBS Scenario
    // ========================================================================

    @Nested
    @DisplayName("Full Multi-DAG SBS Lifecycle")
    class FullMultiDagLifecycle {

        @Test
        @DisplayName("Two DAGs: execute DAG 1 fully, then DAG 2, then rerun DAG 1")
        void twoDags_executeDag1ThenDag2ThenRerunDag1() {
            // === Phase 1: Both triggers ready ===
            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, epochWithReady(Set.of(TRIGGER)), 1,
                    TRIGGER_2, epochWithReady(Set.of(TRIGGER_2)), 1
            );
            assertReady(snapshot, TRIGGER);
            assertReady(snapshot, TRIGGER_2);

            // === Phase 2: Execute DAG 1 fully ===
            snapshot = rebuildMultiDagSnapshot(snapshot,
                    TRIGGER, new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1,
                    TRIGGER_2, epochWithReady(Set.of(TRIGGER_2)), 1
            );
            snapshot = withNodeCompleted(snapshot, TRIGGER);
            snapshot = withNodeCompleted(snapshot, STEP_A);
            snapshot = withNodeCompleted(snapshot, STEP_B);

            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertCompleted(snapshot, STEP_B);
            // DAG 2 still waiting
            assertReady(snapshot, TRIGGER_2);

            // === Phase 3: Execute DAG 2 ===
            snapshot = rebuildMultiDagSnapshot(snapshot,
                    TRIGGER, new EpochState(
                            Set.of(TRIGGER, STEP_A, STEP_B),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1,
                    TRIGGER_2, new EpochState(
                            Set.of(TRIGGER_2, STEP_C, STEP_D),
                            Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1
            );
            snapshot = withNodeCompleted(snapshot, TRIGGER_2);
            snapshot = withNodeCompleted(snapshot, STEP_C);
            snapshot = withNodeCompleted(snapshot, STEP_D);

            // Both DAGs fully completed
            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertCompleted(snapshot, STEP_B);
            assertCompleted(snapshot, TRIGGER_2);
            assertCompleted(snapshot, STEP_C);
            assertCompleted(snapshot, STEP_D);

            // === Phase 4: Rerun DAG 1's trigger (new epoch) ===
            // resetDag creates epoch 2 (active) but epoch 1 also stays active.
            // In real flow, epoch 1 would be closed first. Simulate that:
            // Close epoch 1 for DAG 1, then reset to epoch 2
            DagState dag1Closed = snapshot.getDagState(TRIGGER).closeAndPruneEpoch(1);
            snapshot = rebuildMultiDagSnapshot(snapshot,
                    TRIGGER, epochWithReady(Set.of(TRIGGER)), 2,
                    TRIGGER_2, snapshot.getDagState(TRIGGER_2).currentEpochState(), 1
            );

            // DAG 1: trigger ready for rerun in epoch 2
            assertReady(snapshot, TRIGGER);
            // DAG 1 children: pending in new epoch (fresh EpochState)
            assertThat(snapshot.getDagState(TRIGGER).getEpochState(2).getCompletedNodeIds())
                    .doesNotContain(STEP_A, STEP_B);

            // DAG 2: still fully completed (untouched)
            EpochState dag2State = snapshot.getDagState(TRIGGER_2).currentEpochState();
            assertThat(dag2State.getCompletedNodeIds()).containsExactlyInAnyOrder(TRIGGER_2, STEP_C, STEP_D);
        }

        @Test
        @DisplayName("Parallel execution: DAG 1 at step_b, DAG 2 at step_c")
        void parallelExecution_bothDagsInProgress() {
            StateSnapshot snapshot = buildMultiDagSnapshot(
                    TRIGGER, new EpochState(
                            Set.of(TRIGGER, STEP_A), Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_B), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1,
                    TRIGGER_2, new EpochState(
                            Set.of(TRIGGER_2), Set.of(), Set.of(), Set.of(),
                            Set.of(STEP_C), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1
            );

            // Both DAGs mid-execution
            assertCompleted(snapshot, TRIGGER);
            assertCompleted(snapshot, STEP_A);
            assertReady(snapshot, STEP_B);

            assertCompleted(snapshot, TRIGGER_2);
            assertReady(snapshot, STEP_C);
            assertPending(snapshot, STEP_D);
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty snapshot has no completed, failed, or ready nodes")
        void emptySnapshot() {
            StateSnapshot snapshot = StateSnapshot.empty();
            assertThat(snapshot.getCompletedNodeIds()).isEmpty();
            assertThat(snapshot.getFailedNodeIds()).isEmpty();
            assertThat(snapshot.getReadyNodeIds()).isEmpty();
            assertThat(snapshot.getRunningNodeIds()).isEmpty();
            assertThat(snapshot.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Failed node does not appear in completed or ready")
        void failedNode_notInCompletedOrReady() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(STEP_A), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertThat(snapshot.getFailedNodeIds()).contains(STEP_A);
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);
            assertThat(snapshot.getReadyNodeIds()).doesNotContain(STEP_A);
        }

        @Test
        @DisplayName("Awaiting signal node: not completed, not ready, in awaitingSignalNodeIds")
        void awaitingSignalNode() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(STEP_A),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertThat(snapshot.getAwaitingSignalNodeIds()).contains(STEP_A);
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);
            assertThat(snapshot.getReadyNodeIds()).doesNotContain(STEP_A);
        }

        @Test
        @DisplayName("Skipped node appears in skippedNodeIds, not in completed or ready")
        void skippedNode() {
            StateSnapshot snapshot = buildSnapshot(TRIGGER,
                    new EpochState(
                            Set.of(TRIGGER), Set.of(), Set.of(STEP_A), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1, true);

            assertThat(snapshot.getSkippedNodeIds()).contains(STEP_A);
            assertThat(snapshot.getCompletedNodeIds()).doesNotContain(STEP_A);
            assertThat(snapshot.getReadyNodeIds()).doesNotContain(STEP_A);
        }

        @Test
        @DisplayName("NodeCounts accumulate across epochs and are never reset")
        void nodeCountsAccumulateAcrossEpochs() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Epoch 1: trigger completes
            snapshot = snapshot.markNodeCompleted(TRIGGER, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(1);

            // Epoch 2: trigger completes again
            snapshot = snapshot.markNodeCompleted(TRIGGER, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(2);

            // Epoch 3: trigger fails
            snapshot = snapshot.markNodeFailed(TRIGGER, TRIGGER);
            assertThat(snapshot.getNodes().get(TRIGGER).completed()).isEqualTo(2);
            assertThat(snapshot.getNodes().get(TRIGGER).failed()).isEqualTo(1);
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Build a single-DAG snapshot using the public @JsonCreator constructor.
     */
    private StateSnapshot buildSnapshot(String triggerId, EpochState epochState, int epoch, boolean active) {
        DagState dag = new DagState(epoch, 0, 1,
                Map.of(epoch, epochState),
                active ? Set.of(epoch) : Set.of());
        return createSnapshot(Map.of(triggerId, dag), null, null);
    }

    /**
     * Rebuild a snapshot with a new epoch state (preserving NodeCounts/EdgeCounts).
     */
    private StateSnapshot rebuildSnapshot(StateSnapshot existing, String triggerId,
                                          EpochState epochState, int epoch, boolean active) {
        DagState dag = new DagState(epoch, 0, 1,
                Map.of(epoch, epochState),
                active ? Set.of(epoch) : Set.of());
        return createSnapshot(Map.of(triggerId, dag), existing.getNodes(), existing.getEdges());
    }

    /**
     * Build a multi-DAG snapshot.
     */
    private StateSnapshot buildMultiDagSnapshot(
            String trigger1, EpochState epoch1State, int epoch1,
            String trigger2, EpochState epoch2State, int epoch2) {
        DagState dag1 = new DagState(epoch1, 0, 1,
                Map.of(epoch1, epoch1State), Set.of(epoch1));
        DagState dag2 = new DagState(epoch2, 0, 1,
                Map.of(epoch2, epoch2State), Set.of(epoch2));
        return createSnapshot(Map.of(trigger1, dag1, trigger2, dag2), null, null);
    }

    /**
     * Rebuild a multi-DAG snapshot preserving NodeCounts.
     */
    private StateSnapshot rebuildMultiDagSnapshot(StateSnapshot existing,
            String trigger1, EpochState epoch1State, int epoch1,
            String trigger2, EpochState epoch2State, int epoch2) {
        DagState dag1 = new DagState(epoch1, 0, 1,
                Map.of(epoch1, epoch1State), Set.of(epoch1));
        DagState dag2 = new DagState(epoch2, 0, 1,
                Map.of(epoch2, epoch2State), Set.of(epoch2));
        return createSnapshot(Map.of(trigger1, dag1, trigger2, dag2), existing.getNodes(), existing.getEdges());
    }

    /**
     * Create a StateSnapshot using the public @JsonCreator constructor.
     */
    private StateSnapshot createSnapshot(Map<String, DagState> dags,
                                          Map<String, StateSnapshot.NodeCounts> nodes,
                                          Map<String, StateSnapshot.EdgeCounts> edges) {
        return new StateSnapshot(
                3, 0L, dags,
                null, null, null, null, null,  // flat fields computed from dags
                nodes, edges,
                null, null, null, null,         // decisionBranches, loops, splits, awaitingSignal
                0L, null                        // totalDurationMs, lastUpdated
        );
    }

    /**
     * Create an EpochState with only ready nodes.
     */
    private EpochState epochWithReady(Set<String> readyNodes) {
        return new EpochState(
                Set.of(), Set.of(), Set.of(), Set.of(),
                readyNodes, Set.of(),
                Map.of(), Map.of(), Map.of(), null
        );
    }

    /**
     * Add a COMPLETED increment to global NodeCounts only (without touching epoch state).
     * Used to simulate NodeCounts from a previous epoch without polluting the current epoch.
     */
    private StateSnapshot withNodeCompleted(StateSnapshot snapshot, String nodeId) {
        return snapshot.incrementNodeCount(nodeId, "COMPLETED");
    }

    /**
     * Simulate a node transition: set completed nodes and ready nodes in the epoch.
     */
    private StateSnapshot transitionNode(StateSnapshot snapshot, String triggerId,
                                          Set<String> completed, Set<String> ready) {
        EpochState newEpoch = new EpochState(
                completed, Set.of(), Set.of(), Set.of(),
                ready, Set.of(),
                Map.of(), Map.of(), Map.of(), null
        );
        int epoch = snapshot.getDagState(triggerId).getCurrentEpoch();
        return rebuildSnapshot(snapshot, triggerId, newEpoch, epoch, true);
    }

    // ========================================================================
    // Assertion Helpers
    // ========================================================================

    private void assertReady(StateSnapshot snapshot, String nodeId) {
        assertThat(snapshot.getReadyNodeIds())
                .as("Node %s should be READY", nodeId)
                .contains(nodeId);
        assertThat(snapshot.getCompletedNodeIds())
                .as("Node %s should NOT be COMPLETED when READY", nodeId)
                .doesNotContain(nodeId);
    }

    private void assertCompleted(StateSnapshot snapshot, String nodeId) {
        assertThat(snapshot.getCompletedNodeIds())
                .as("Node %s should be COMPLETED", nodeId)
                .contains(nodeId);
        assertThat(snapshot.getReadyNodeIds())
                .as("Node %s should NOT be READY when COMPLETED", nodeId)
                .doesNotContain(nodeId);
    }

    private void assertPending(StateSnapshot snapshot, String nodeId) {
        assertThat(snapshot.getCompletedNodeIds())
                .as("Node %s should NOT be COMPLETED when PENDING", nodeId)
                .doesNotContain(nodeId);
        assertThat(snapshot.getReadyNodeIds())
                .as("Node %s should NOT be READY when PENDING", nodeId)
                .doesNotContain(nodeId);
        assertThat(snapshot.getRunningNodeIds())
                .as("Node %s should NOT be RUNNING when PENDING", nodeId)
                .doesNotContain(nodeId);
        assertThat(snapshot.getFailedNodeIds())
                .as("Node %s should NOT be FAILED when PENDING", nodeId)
                .doesNotContain(nodeId);
    }
}
