package com.apimarketplace.orchestrator.execution.v2;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Advanced Step-by-Step (SBS) tests: multi-DAG interleaving, decision branching,
 * fork/merge, deep rerun chains, epoch cycling, and complex topologies.
 *
 * <p>Topologies used:
 * <pre>
 * 3-DAG:
 *   trigger:webhook  → mcp:fetch_data → mcp:transform → mcp:store
 *   trigger:schedule → mcp:cleanup   → mcp:notify
 *   trigger:manual   → mcp:validate  → core:decision → (if) mcp:approve → (else) mcp:reject
 *
 * Diamond (fork/merge):
 *   trigger:start → core:fork → [mcp:branch_a, mcp:branch_b] → core:merge → mcp:final
 *
 * Deep chain:
 *   trigger:start → mcp:s1 → mcp:s2 → mcp:s3 → mcp:s4 → mcp:s5
 * </pre>
 */
@DisplayName("Step-by-Step Advanced Scenarios")
class StepByStepAdvancedScenariosTest {

    // ========================================================================
    // 3-DAG topology
    // ========================================================================
    private static final String T_WEBHOOK = "trigger:webhook";
    private static final String FETCH = "mcp:fetch_data";
    private static final String TRANSFORM = "mcp:transform";
    private static final String STORE = "mcp:store";

    private static final String T_SCHEDULE = "trigger:schedule";
    private static final String CLEANUP = "mcp:cleanup";
    private static final String NOTIFY = "mcp:notify";

    private static final String T_MANUAL = "trigger:manual";
    private static final String VALIDATE = "mcp:validate";
    private static final String DECISION = "core:decision";
    private static final String APPROVE = "mcp:approve";
    private static final String REJECT = "mcp:reject";

    // Diamond topology
    private static final String T_START = "trigger:start";
    private static final String FORK = "core:fork";
    private static final String BRANCH_A = "mcp:branch_a";
    private static final String BRANCH_B = "mcp:branch_b";
    private static final String MERGE = "core:merge";
    private static final String FINAL = "mcp:final";

    // Deep chain
    private static final String S1 = "mcp:s1";
    private static final String S2 = "mcp:s2";
    private static final String S3 = "mcp:s3";
    private static final String S4 = "mcp:s4";
    private static final String S5 = "mcp:s5";

    // ========================================================================
    // 3-DAG Interleaved Execution
    // ========================================================================

    @Nested
    @DisplayName("3-DAG - Interleaved Execution")
    class ThreeDagInterleaved {

        @Test
        @DisplayName("Init: all 3 triggers READY, all children PENDING")
        void init_allTriggersReady() {
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(), Set.of(T_WEBHOOK)),
                    epoch(Set.of(), Set.of(T_SCHEDULE)),
                    epoch(Set.of(), Set.of(T_MANUAL))
            );

            assertReady(s, T_WEBHOOK);
            assertReady(s, T_SCHEDULE);
            assertReady(s, T_MANUAL);
            assertPending(s, FETCH);
            assertPending(s, CLEANUP);
            assertPending(s, VALIDATE);
        }

        @Test
        @DisplayName("Execute triggers in interleaved order: webhook, manual, schedule")
        void executeTriggersInterleaved() {
            // Step 1: Fire webhook trigger
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK), Set.of(FETCH)),
                    epoch(Set.of(), Set.of(T_SCHEDULE)),
                    epoch(Set.of(), Set.of(T_MANUAL))
            );
            assertCompleted(s, T_WEBHOOK);
            assertReady(s, FETCH);
            assertReady(s, T_SCHEDULE);
            assertReady(s, T_MANUAL);

            // Step 2: Fire manual trigger (before finishing webhook DAG)
            s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK), Set.of(FETCH)),
                    epoch(Set.of(), Set.of(T_SCHEDULE)),
                    epoch(Set.of(T_MANUAL), Set.of(VALIDATE))
            );
            assertReady(s, FETCH);
            assertReady(s, T_SCHEDULE);
            assertCompleted(s, T_MANUAL);
            assertReady(s, VALIDATE);

            // Step 3: Execute fetch_data from webhook DAG
            s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH), Set.of(TRANSFORM)),
                    epoch(Set.of(), Set.of(T_SCHEDULE)),
                    epoch(Set.of(T_MANUAL), Set.of(VALIDATE))
            );
            assertCompleted(s, FETCH);
            assertReady(s, TRANSFORM);
            assertReady(s, T_SCHEDULE);
            assertReady(s, VALIDATE);
        }

        @Test
        @DisplayName("Complete DAG 1 while DAG 2 and DAG 3 are in progress")
        void completeDag1WhileOthersInProgress() {
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH, TRANSFORM, STORE), Set.of()),
                    epoch(Set.of(T_SCHEDULE, CLEANUP), Set.of(NOTIFY)),
                    epoch(Set.of(T_MANUAL, VALIDATE), Set.of(DECISION))
            );

            // DAG 1: fully completed, no ready nodes
            assertCompleted(s, T_WEBHOOK);
            assertCompleted(s, FETCH);
            assertCompleted(s, TRANSFORM);
            assertCompleted(s, STORE);

            // DAG 2: in progress
            assertCompleted(s, T_SCHEDULE);
            assertCompleted(s, CLEANUP);
            assertReady(s, NOTIFY);

            // DAG 3: in progress
            assertCompleted(s, T_MANUAL);
            assertCompleted(s, VALIDATE);
            assertReady(s, DECISION);
            assertPending(s, APPROVE);
            assertPending(s, REJECT);
        }

        @Test
        @DisplayName("Rerun DAG 2 trigger while DAG 1 complete, DAG 3 in progress")
        void rerunDag2TriggerWhileOthersBusy() {
            // DAG 1: fully completed, DAG 2: fully completed, DAG 3: in progress
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH, TRANSFORM, STORE), Set.of()),
                    epoch(Set.of(T_SCHEDULE, CLEANUP, NOTIFY), Set.of()),
                    epoch(Set.of(T_MANUAL, VALIDATE), Set.of(DECISION))
            );

            // Close DAG 2 epoch 1, open epoch 2 with trigger ready
            s = rebuildDag(s, T_SCHEDULE, epoch(Set.of(), Set.of(T_SCHEDULE)), 2);

            // DAG 1: still fully completed
            assertCompleted(s, T_WEBHOOK);
            assertCompleted(s, STORE);

            // DAG 2: trigger ready for rerun
            assertReady(s, T_SCHEDULE);
            assertPending(s, CLEANUP);
            assertPending(s, NOTIFY);

            // DAG 3: unchanged, in progress
            assertCompleted(s, VALIDATE);
            assertReady(s, DECISION);
        }

        @Test
        @DisplayName("All 3 DAGs at different stages, rerun node in DAG 3")
        void rerunNodeInDag3WhileOthersBusy() {
            // DAG 1: transform ready
            // DAG 2: fully completed
            // DAG 3: validate + decision completed, approve ready
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH), Set.of(TRANSFORM)),
                    epoch(Set.of(T_SCHEDULE, CLEANUP, NOTIFY), Set.of()),
                    epoch(Set.of(T_MANUAL, VALIDATE, DECISION, APPROVE), Set.of())
            );

            // Rerun validate in DAG 3 → reset validate + decision + approve + reject (downstream)
            s = s.resetDag(Set.of(VALIDATE, DECISION, APPROVE, REJECT));

            // DAG 1: unchanged
            assertCompleted(s, T_WEBHOOK);
            assertCompleted(s, FETCH);
            // TRANSFORM was in ready before reset, should still be okay because
            // it's NOT in the reset set - but it was in DAG 1's ready, not DAG 3's completed.
            // Let's verify DAG 1 epoch is untouched:
            EpochState dag1 = s.getDagState(T_WEBHOOK).currentEpochState();
            assertThat(dag1.getCompletedNodeIds()).containsExactlyInAnyOrder(T_WEBHOOK, FETCH);

            // DAG 2: unchanged
            EpochState dag2 = s.getDagState(T_SCHEDULE).currentEpochState();
            assertThat(dag2.getCompletedNodeIds()).containsExactlyInAnyOrder(T_SCHEDULE, CLEANUP, NOTIFY);

            // DAG 3: validate + decision + approve removed, manual stays
            assertCompleted(s, T_MANUAL);
            assertThat(s.getCompletedNodeIds()).doesNotContain(VALIDATE, DECISION, APPROVE);
        }
    }

    // ========================================================================
    // 3-DAG - Decision Branching
    // ========================================================================

    @Nested
    @DisplayName("3-DAG - Decision Branching in DAG 3")
    class ThreeDagDecisionBranching {

        @Test
        @DisplayName("Decision takes IF branch: approve READY, reject SKIPPED")
        void decisionIfBranch_approveReady_rejectSkipped() {
            // DAG 3: trigger + validate + decision completed, IF branch taken
            EpochState dag3 = new EpochState(
                    Set.of(T_MANUAL, VALIDATE, DECISION),
                    Set.of(), Set.of(REJECT), Set.of(),
                    Set.of(APPROVE), Set.of(),
                    Map.of(DECISION, Set.of("if")),
                    Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_MANUAL, dag3, 1);

            assertCompleted(s, DECISION);
            assertReady(s, APPROVE);
            assertThat(s.getSkippedNodeIds()).contains(REJECT);
            assertThat(s.getDecisionBranches().get(DECISION)).contains("if");
        }

        @Test
        @DisplayName("Decision takes ELSE branch: reject READY, approve SKIPPED")
        void decisionElseBranch_rejectReady_approveSkipped() {
            EpochState dag3 = new EpochState(
                    Set.of(T_MANUAL, VALIDATE, DECISION),
                    Set.of(), Set.of(APPROVE), Set.of(),
                    Set.of(REJECT), Set.of(),
                    Map.of(DECISION, Set.of("else")),
                    Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_MANUAL, dag3, 1);

            assertReady(s, REJECT);
            assertThat(s.getSkippedNodeIds()).contains(APPROVE);
        }

        @Test
        @DisplayName("Rerun decision: clears branch and both approve/reject, approve/reject become PENDING")
        void rerunDecision_clearsBranch_childrenPending() {
            // Full execution with IF branch
            EpochState dag3 = new EpochState(
                    Set.of(T_MANUAL, VALIDATE, DECISION, APPROVE),
                    Set.of(), Set.of(REJECT), Set.of(),
                    Set.of(), Set.of(),
                    Map.of(DECISION, Set.of("if")),
                    Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_MANUAL, dag3, 1);
            s = withNodeCounts(s, T_MANUAL, VALIDATE, DECISION, APPROVE);

            // Rerun decision + downstream
            s = s.resetDag(Set.of(DECISION, APPROVE, REJECT));

            // Decision + approve removed from completed, reject removed from skipped
            assertThat(s.getCompletedNodeIds()).doesNotContain(DECISION, APPROVE);
            assertThat(s.getSkippedNodeIds()).doesNotContain(REJECT);

            // Decision branches cleared
            assertThat(s.getDecisionBranches()).doesNotContainKey(DECISION);

            // trigger + validate stay
            assertCompleted(s, T_MANUAL);
            assertCompleted(s, VALIDATE);
        }

        @Test
        @DisplayName("Rerun decision in 3-DAG: only DAG 3 affected")
        void rerunDecisionIn3Dag_otherDagsUntouched() {
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH, TRANSFORM, STORE), Set.of()),
                    epoch(Set.of(T_SCHEDULE, CLEANUP, NOTIFY), Set.of()),
                    new EpochState(
                            Set.of(T_MANUAL, VALIDATE, DECISION, APPROVE),
                            Set.of(), Set.of(REJECT), Set.of(), Set.of(), Set.of(),
                            Map.of(DECISION, Set.of("if")),
                            Map.of(), Map.of(), null
                    )
            );

            s = s.resetDag(Set.of(DECISION, APPROVE, REJECT));

            // DAG 1: untouched
            assertCompleted(s, T_WEBHOOK);
            assertCompleted(s, STORE);
            // DAG 2: untouched
            assertCompleted(s, T_SCHEDULE);
            assertCompleted(s, NOTIFY);
            // DAG 3: decision reset, manual + validate stay
            assertCompleted(s, T_MANUAL);
            assertCompleted(s, VALIDATE);
            assertThat(s.getCompletedNodeIds()).doesNotContain(DECISION, APPROVE);
        }
    }

    // ========================================================================
    // Diamond Topology (Fork / Merge)
    // ========================================================================

    @Nested
    @DisplayName("Diamond Topology - Fork / Merge")
    class DiamondTopology {

        @Test
        @DisplayName("After fork: both branches READY")
        void afterFork_bothBranchesReady() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK), Set.of(BRANCH_A, BRANCH_B)), 1);

            assertCompleted(s, T_START);
            assertCompleted(s, FORK);
            assertReady(s, BRANCH_A);
            assertReady(s, BRANCH_B);
            assertPending(s, MERGE);
            assertPending(s, FINAL);
        }

        @Test
        @DisplayName("Execute branch_a: branch_a COMPLETED, branch_b still READY, merge PENDING")
        void executeBranchA_mergePending() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK, BRANCH_A), Set.of(BRANCH_B)), 1);

            assertCompleted(s, BRANCH_A);
            assertReady(s, BRANCH_B);
            assertPending(s, MERGE); // merge waits for ALL predecessors
        }

        @Test
        @DisplayName("Execute both branches: merge becomes READY")
        void executeBothBranches_mergeReady() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK, BRANCH_A, BRANCH_B), Set.of(MERGE)), 1);

            assertCompleted(s, BRANCH_A);
            assertCompleted(s, BRANCH_B);
            assertReady(s, MERGE);
            assertPending(s, FINAL);
        }

        @Test
        @DisplayName("Complete diamond: all COMPLETED")
        void completeDiamond_allCompleted() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK, BRANCH_A, BRANCH_B, MERGE, FINAL), Set.of()), 1);

            assertCompleted(s, T_START);
            assertCompleted(s, FORK);
            assertCompleted(s, BRANCH_A);
            assertCompleted(s, BRANCH_B);
            assertCompleted(s, MERGE);
            assertCompleted(s, FINAL);
            assertThat(s.getReadyNodeIds()).isEmpty();
        }

        @Test
        @DisplayName("Rerun branch_a only: branch_a reset, branch_b stays, merge + final reset")
        void rerunBranchA_mergeReset() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK, BRANCH_A, BRANCH_B, MERGE, FINAL), Set.of()), 1);
            s = withNodeCounts(s, T_START, FORK, BRANCH_A, BRANCH_B, MERGE, FINAL);

            // Rerun branch_a: reset branch_a + merge + final (downstream of branch_a)
            // branch_b is NOT downstream of branch_a, so it stays
            s = s.resetDag(Set.of(BRANCH_A, MERGE, FINAL));

            assertCompleted(s, T_START);
            assertCompleted(s, FORK);
            assertThat(s.getCompletedNodeIds()).doesNotContain(BRANCH_A, MERGE, FINAL);
            assertCompleted(s, BRANCH_B); // sibling stays
        }

        @Test
        @DisplayName("Rerun fork: all downstream reset including both branches")
        void rerunFork_allDownstreamReset() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, FORK, BRANCH_A, BRANCH_B, MERGE, FINAL), Set.of()), 1);

            s = s.resetDag(Set.of(FORK, BRANCH_A, BRANCH_B, MERGE, FINAL));

            assertCompleted(s, T_START); // trigger stays
            assertThat(s.getCompletedNodeIds()).doesNotContain(FORK, BRANCH_A, BRANCH_B, MERGE, FINAL);
        }

        @Test
        @DisplayName("Rerun branch_a after it failed: branch_a was FAILED, reset clears failure")
        void rerunBranchA_afterFailure_clearsFailure() {
            EpochState es = new EpochState(
                    Set.of(T_START, FORK, BRANCH_B),
                    Set.of(BRANCH_A),     // branch_a FAILED
                    Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_START, es, 1);
            assertThat(s.getFailedNodeIds()).contains(BRANCH_A);

            // Rerun branch_a
            s = s.resetDag(Set.of(BRANCH_A, MERGE, FINAL));
            assertThat(s.getFailedNodeIds()).doesNotContain(BRANCH_A);
            assertThat(s.getCompletedNodeIds()).doesNotContain(BRANCH_A);
            // branch_b stays completed
            assertCompleted(s, BRANCH_B);
        }
    }

    // ========================================================================
    // Deep Chain - Cascading Rerun
    // ========================================================================

    @Nested
    @DisplayName("Deep Chain - Cascading Rerun")
    class DeepChainCascadingRerun {

        @Test
        @DisplayName("Rerun s2 in 5-node chain: s2-s5 reset, trigger + s1 stay")
        void rerunS2_cascadesDownstream() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1, S2, S3, S4, S5), Set.of()), 1);
            s = withNodeCounts(s, T_START, S1, S2, S3, S4, S5);

            s = s.resetDag(Set.of(S2, S3, S4, S5));

            assertCompleted(s, T_START);
            assertCompleted(s, S1);
            assertThat(s.getCompletedNodeIds()).doesNotContain(S2, S3, S4, S5);

            // NodeCounts preserved
            assertThat(s.getNodes().get(S2).completed()).isEqualTo(1);
            assertThat(s.getNodes().get(S5).completed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Rerun s1 in deep chain: everything downstream resets (s1-s5)")
        void rerunS1_resetsEverythingExceptTrigger() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1, S2, S3, S4, S5), Set.of()), 1);

            s = s.resetDag(Set.of(S1, S2, S3, S4, S5));

            assertCompleted(s, T_START);
            assertThat(s.getCompletedNodeIds()).containsExactly(T_START);
        }

        @Test
        @DisplayName("Multiple sequential reruns: rerun s3, then s2 - all cascades correct")
        void multipleSequentialReruns() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1, S2, S3, S4, S5), Set.of()), 1);
            s = withNodeCounts(s, T_START, S1, S2, S3, S4, S5);

            // First rerun: s3 → s3,s4,s5 reset
            s = s.resetDag(Set.of(S3, S4, S5));
            assertCompleted(s, S1);
            assertCompleted(s, S2);
            assertThat(s.getCompletedNodeIds()).doesNotContain(S3, S4, S5);

            // Re-execute s3 (markNodeCompleted increments NodeCounts internally)
            s = s.markNodeCompleted(T_START, S3);

            // Now rerun s2 (which cascades to s3,s4,s5 again)
            s = s.resetDag(Set.of(S2, S3, S4, S5));
            assertCompleted(s, S1);
            assertThat(s.getCompletedNodeIds()).doesNotContain(S2, S3, S4, S5);

            // S3 was completed twice (once in initial run via withNodeCounts, once via markNodeCompleted)
            assertThat(s.getNodes().get(S3).completed()).isEqualTo(2);
        }

        @Test
        @DisplayName("Rerun s4 at depth 4: only s4 and s5 reset")
        void rerunS4_onlyS4S5Reset() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1, S2, S3, S4, S5), Set.of()), 1);

            s = s.resetDag(Set.of(S4, S5));

            assertCompleted(s, T_START);
            assertCompleted(s, S1);
            assertCompleted(s, S2);
            assertCompleted(s, S3);
            assertThat(s.getCompletedNodeIds()).doesNotContain(S4, S5);
        }
    }

    // ========================================================================
    // Multi-DAG Epoch Cycling (Reusable Triggers)
    // ========================================================================

    @Nested
    @DisplayName("Multi-DAG - Epoch Cycling")
    class MultiDagEpochCycling {

        @Test
        @DisplayName("DAG 1 at epoch 3, DAG 2 at epoch 1 - independent epoch counters")
        void independentEpochCounters() {
            DagState dag1 = DagState.initial()
                    .advanceEpoch(1).advanceEpoch(2).advanceEpoch(3);
            DagState dag2 = DagState.initial().advanceEpoch(1);

            StateSnapshot s = createSnapshot(
                    Map.of(T_WEBHOOK, dag1, T_SCHEDULE, dag2), null, null);

            assertThat(s.getDagState(T_WEBHOOK).getCurrentEpoch()).isEqualTo(3);
            assertThat(s.getDagState(T_WEBHOOK).getFireCount()).isEqualTo(3);
            assertThat(s.getDagState(T_SCHEDULE).getCurrentEpoch()).isEqualTo(1);
            assertThat(s.getDagState(T_SCHEDULE).getFireCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Close epoch in DAG 1, prepare next cycle, fire again - full cycle")
        void fullEpochCycleInDag1() {
            // Epoch 1: completed
            StateSnapshot s = buildSingleDagSnapshot(T_WEBHOOK,
                    epoch(Set.of(T_WEBHOOK, FETCH, TRANSFORM, STORE), Set.of()), 1);
            s = withNodeCounts(s, T_WEBHOOK, FETCH, TRANSFORM, STORE);

            // Close epoch 1 (must use snapshot-level method to propagate to flat views)
            s = s.closeAndPruneEpochForDag(T_WEBHOOK, 1);
            assertThat(s.getDagState(T_WEBHOOK).getActiveEpochs()).isEmpty();

            // Prepare next cycle (dormant epoch 2 with trigger ready)
            s = s.prepareDagForNextCycle(T_WEBHOOK, 2, T_WEBHOOK);
            assertThat(s.getDagState(T_WEBHOOK).getActiveEpochs()).doesNotContain(2);
            // Trigger shows as ready in flat view via currentEpochState fallback
            assertThat(s.getReadyNodeIds()).contains(T_WEBHOOK);

            // Open epoch 2 (trigger fires)
            s = s.openEpochForDag(T_WEBHOOK, 2);
            assertThat(s.getDagState(T_WEBHOOK).getActiveEpochs()).contains(2);

            // Execute trigger in epoch 2
            s = rebuildDag(s, T_WEBHOOK, epoch(Set.of(T_WEBHOOK), Set.of(FETCH)), 2);
            s = s.incrementNodeCount(T_WEBHOOK, "COMPLETED");

            assertCompleted(s, T_WEBHOOK);
            assertReady(s, FETCH);
            assertThat(s.getNodes().get(T_WEBHOOK).completed()).isEqualTo(2); // epoch1 + epoch2
        }

        @Test
        @DisplayName("3 epochs completed - NodeCounts accumulate correctly")
        void threeEpochsNodeCountsAccumulate() {
            StateSnapshot s = StateSnapshot.empty();

            // Epoch 1
            s = s.markNodeCompleted(T_WEBHOOK, T_WEBHOOK);
            s = s.markNodeCompleted(T_WEBHOOK, FETCH);
            s = s.markNodeFailed(T_WEBHOOK, TRANSFORM);

            // Epoch 2
            s = s.markNodeCompleted(T_WEBHOOK, T_WEBHOOK);
            s = s.markNodeCompleted(T_WEBHOOK, FETCH);
            s = s.markNodeCompleted(T_WEBHOOK, TRANSFORM);
            s = s.markNodeCompleted(T_WEBHOOK, STORE);

            // Epoch 3
            s = s.markNodeCompleted(T_WEBHOOK, T_WEBHOOK);
            s = s.markNodeCompleted(T_WEBHOOK, FETCH);
            s = s.markNodeSkipped(T_WEBHOOK, TRANSFORM);

            assertThat(s.getNodes().get(T_WEBHOOK).completed()).isEqualTo(3);
            assertThat(s.getNodes().get(FETCH).completed()).isEqualTo(3);
            assertThat(s.getNodes().get(TRANSFORM).completed()).isEqualTo(1);
            assertThat(s.getNodes().get(TRANSFORM).failed()).isEqualTo(1);
            assertThat(s.getNodes().get(TRANSFORM).skipped()).isEqualTo(1);
            assertThat(s.getNodes().get(STORE).completed()).isEqualTo(1);
        }
    }

    // ========================================================================
    // Multi-DAG - Awaiting Signal Interplay
    // ========================================================================

    @Nested
    @DisplayName("Multi-DAG - Awaiting Signal")
    class MultiDagAwaitingSignal {

        @Test
        @DisplayName("DAG 1 awaiting signal, DAG 2 can still execute")
        void dag1AwaitingSignal_dag2CanExecute() {
            StateSnapshot s = createSnapshot(Map.of(
                    T_WEBHOOK, dagWith(new EpochState(
                            Set.of(T_WEBHOOK), Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(FETCH),  // fetch awaiting signal
                            Map.of(), Map.of(), Map.of(), null
                    ), 1),
                    T_SCHEDULE, dagWith(epoch(Set.of(T_SCHEDULE), Set.of(CLEANUP)), 1)
            ), null, null);

            assertThat(s.getAwaitingSignalNodeIds()).contains(FETCH);
            assertCompleted(s, T_SCHEDULE);
            assertReady(s, CLEANUP);
        }

        @Test
        @DisplayName("Signal resolved in DAG 1: node moves from AWAITING to COMPLETED")
        void signalResolved_nodeCompleted() {
            StateSnapshot s = buildSingleDagSnapshot(T_WEBHOOK,
                    new EpochState(
                            Set.of(T_WEBHOOK), Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(FETCH),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1);

            assertThat(s.getAwaitingSignalNodeIds()).contains(FETCH);

            // Resolve signal
            s = s.resolveAwaitingSignal(T_WEBHOOK, FETCH);

            assertThat(s.getAwaitingSignalNodeIds()).doesNotContain(FETCH);
            assertCompleted(s, FETCH);
            assertThat(s.getNodes().get(FETCH).completed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Rerun an awaiting-signal node: clears awaiting state")
        void rerunAwaitingSignalNode_clearsState() {
            StateSnapshot s = buildSingleDagSnapshot(T_WEBHOOK,
                    new EpochState(
                            Set.of(T_WEBHOOK), Set.of(), Set.of(), Set.of(),
                            Set.of(), Set.of(FETCH),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1);

            s = s.resetDag(Set.of(FETCH, TRANSFORM, STORE));

            assertThat(s.getAwaitingSignalNodeIds()).doesNotContain(FETCH);
            assertThat(s.getCompletedNodeIds()).doesNotContain(FETCH);
            assertCompleted(s, T_WEBHOOK);
        }
    }

    // ========================================================================
    // Stress: Mixed Node Types, Many Nodes
    // ========================================================================

    @Nested
    @DisplayName("Stress - Many Nodes and Mixed States")
    class StressManyNodes {

        @Test
        @DisplayName("10 nodes: mixed completed, failed, skipped, ready, running, awaiting")
        void tenNodesAllStates() {
            EpochState es = new EpochState(
                    Set.of("mcp:n1", "mcp:n2"),       // completed
                    Set.of("mcp:n3"),                  // failed
                    Set.of("mcp:n4", "mcp:n5"),        // skipped
                    Set.of("mcp:n6"),                  // running
                    Set.of("mcp:n7", "mcp:n8"),        // ready
                    Set.of("mcp:n9"),                  // awaiting signal
                    Map.of(), Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_START, es, 1);

            assertThat(s.getCompletedNodeIds()).containsExactlyInAnyOrder("mcp:n1", "mcp:n2");
            assertThat(s.getFailedNodeIds()).containsExactly("mcp:n3");
            assertThat(s.getSkippedNodeIds()).containsExactlyInAnyOrder("mcp:n4", "mcp:n5");
            assertThat(s.getRunningNodeIds()).containsExactly("mcp:n6");
            assertThat(s.getReadyNodeIds()).containsExactlyInAnyOrder("mcp:n7", "mcp:n8");
            assertThat(s.getAwaitingSignalNodeIds()).containsExactly("mcp:n9");

            // mcp:n10 not in any set = PENDING
            assertPending(s, "mcp:n10");
        }

        @Test
        @DisplayName("Selective reset on 5 of 10 nodes: other 5 unchanged")
        void selectiveResetHalfOfNodes() {
            EpochState es = new EpochState(
                    Set.of("n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9", "n10"),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), null
            );
            StateSnapshot s = buildSingleDagSnapshot(T_START, es, 1);

            // Reset half
            s = s.resetDag(Set.of("n6", "n7", "n8", "n9", "n10"));

            assertThat(s.getCompletedNodeIds())
                    .containsExactlyInAnyOrder("n1", "n2", "n3", "n4", "n5");
            assertThat(s.getCompletedNodeIds())
                    .doesNotContain("n6", "n7", "n8", "n9", "n10");
        }
    }

    // ========================================================================
    // DagState - Spawn Management
    // ========================================================================

    @Nested
    @DisplayName("DagState - Spawn Management")
    class SpawnManagement {

        @Test
        @DisplayName("Multiple spawns within same epoch: spawn increments, epoch stays")
        void multipleSpawnsWithinEpoch() {
            DagState dag = DagState.initial().advanceEpoch(1);
            assertThat(dag.getCurrentSpawn()).isEqualTo(0);
            assertThat(dag.getCurrentEpoch()).isEqualTo(1);

            dag = dag.advanceSpawn();
            assertThat(dag.getCurrentSpawn()).isEqualTo(1);
            assertThat(dag.getCurrentEpoch()).isEqualTo(1);

            dag = dag.advanceSpawn();
            assertThat(dag.getCurrentSpawn()).isEqualTo(2);
            assertThat(dag.getCurrentEpoch()).isEqualTo(1);

            // New epoch resets spawn to 0
            dag = dag.advanceEpoch(2);
            assertThat(dag.getCurrentSpawn()).isEqualTo(0);
            assertThat(dag.getCurrentEpoch()).isEqualTo(2);
        }

        @Test
        @DisplayName("Spawn counter preserved across EpochState mutations")
        void spawnPreservedAcrossEpochStateMutations() {
            DagState dag = DagState.initial().advanceEpoch(1).advanceSpawn().advanceSpawn();
            assertThat(dag.getCurrentSpawn()).isEqualTo(2);

            // Mutate epoch state
            dag = dag.withCurrentEpochState(dag.currentEpochState().markNodeCompleted(FETCH));
            assertThat(dag.getCurrentSpawn()).isEqualTo(2); // unchanged
        }
    }

    // ========================================================================
    // Edge Counts
    // ========================================================================

    @Nested
    @DisplayName("Edge Counts - Global Accumulation")
    class EdgeCounts {

        @Test
        @DisplayName("Edge counts accumulate across epochs and are never reset")
        void edgeCountsAccumulate() {
            StateSnapshot s = StateSnapshot.empty();

            s = s.incrementEdge(T_WEBHOOK, FETCH, "COMPLETED");
            s = s.incrementEdge(T_WEBHOOK, FETCH, "COMPLETED");
            s = s.incrementEdge(T_WEBHOOK, FETCH, "SKIPPED");

            StateSnapshot.EdgeCounts ec = s.getEdges().get(T_WEBHOOK + "->" + FETCH);
            assertThat(ec.completed()).isEqualTo(2);
            assertThat(ec.skipped()).isEqualTo(1);
            assertThat(ec.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("resetDag does NOT reset edge counts")
        void resetDagDoesNotResetEdgeCounts() {
            StateSnapshot s = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1), Set.of()), 1);
            s = s.incrementEdge(T_START, S1, "COMPLETED");

            s = s.resetDag(Set.of(S1));

            assertThat(s.getEdges().get(T_START + "->" + S1).completed()).isEqualTo(1);
        }
    }

    // ========================================================================
    // Flat View Computation - Complex Multi-DAG
    // ========================================================================

    @Nested
    @DisplayName("Flat View - Union Across DAGs")
    class FlatViewComputation {

        @Test
        @DisplayName("Flat views union nodes from all active DAGs")
        void flatViewsUnionAllActiveDags() {
            StateSnapshot s = createSnapshot(Map.of(
                    T_WEBHOOK, dagWith(epoch(Set.of(T_WEBHOOK, FETCH), Set.of(TRANSFORM)), 1),
                    T_SCHEDULE, dagWith(epoch(Set.of(T_SCHEDULE), Set.of(CLEANUP)), 1),
                    T_MANUAL, dagWith(new EpochState(
                            Set.of(T_MANUAL), Set.of(VALIDATE), Set.of(), Set.of(),
                            Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), null
                    ), 1)
            ), null, null);

            // Completed = union
            assertThat(s.getCompletedNodeIds())
                    .containsExactlyInAnyOrder(T_WEBHOOK, FETCH, T_SCHEDULE, T_MANUAL);

            // Ready = union
            assertThat(s.getReadyNodeIds())
                    .containsExactlyInAnyOrder(TRANSFORM, CLEANUP);

            // Failed
            assertThat(s.getFailedNodeIds()).containsExactly(VALIDATE);
        }

        @Test
        @DisplayName("Inactive DAG (no active epochs) excluded from flat views")
        void inactiveDagExcludedFromFlatViews() {
            DagState activeDag = dagWith(epoch(Set.of(T_WEBHOOK, FETCH), Set.of()), 1);
            DagState inactiveDag = DagState.initial()
                    .advanceEpoch(1)
                    .withCurrentEpochState(
                            EpochState.fresh().markNodeCompleted(T_SCHEDULE).markNodeCompleted(CLEANUP))
                    .closeAndPruneEpoch(1);

            StateSnapshot s = createSnapshot(
                    Map.of(T_WEBHOOK, activeDag, T_SCHEDULE, inactiveDag), null, null);

            // Active DAG's nodes visible
            assertThat(s.getCompletedNodeIds()).containsExactlyInAnyOrder(T_WEBHOOK, FETCH);
            // Inactive DAG's nodes NOT visible
            assertThat(s.getCompletedNodeIds()).doesNotContain(T_SCHEDULE, CLEANUP);
        }

        @Test
        @DisplayName("Same node ID in two DAGs (shared step): appears in flat view when in either")
        void sharedNodeAppearsFromEitherDag() {
            // Unusual case: same step reached via two triggers
            String sharedStep = "mcp:shared";
            StateSnapshot s = createSnapshot(Map.of(
                    T_WEBHOOK, dagWith(epoch(Set.of(T_WEBHOOK, sharedStep), Set.of()), 1),
                    T_SCHEDULE, dagWith(epoch(Set.of(T_SCHEDULE), Set.of(sharedStep)), 1)
            ), null, null);

            // sharedStep in completed (from DAG 1) AND in ready (from DAG 2)
            // flat views are unions, so both appear
            assertThat(s.getCompletedNodeIds()).contains(sharedStep);
            assertThat(s.getReadyNodeIds()).contains(sharedStep);
        }
    }

    // ========================================================================
    // Immutability Guarantees
    // ========================================================================

    @Nested
    @DisplayName("Immutability Guarantees")
    class ImmutabilityGuarantees {

        @Test
        @DisplayName("markNodeCompleted returns new instance, original unchanged")
        void markNodeCompleted_immutable() {
            StateSnapshot original = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START), Set.of(S1)), 1);
            StateSnapshot modified = original.markNodeCompleted(T_START, S1);

            // Original unchanged
            assertThat(original.getCompletedNodeIds()).doesNotContain(S1);
            assertReady(original, S1);

            // Modified has the change
            assertCompleted(modified, S1);
        }

        @Test
        @DisplayName("resetDag returns new instance, original unchanged")
        void resetDag_immutable() {
            StateSnapshot original = buildSingleDagSnapshot(T_START,
                    epoch(Set.of(T_START, S1, S2), Set.of()), 1);
            StateSnapshot modified = original.resetDag(Set.of(S1, S2));

            // Original unchanged
            assertCompleted(original, S1);
            assertCompleted(original, S2);

            // Modified has reset
            assertThat(modified.getCompletedNodeIds()).doesNotContain(S1, S2);
        }

        @Test
        @DisplayName("EpochState.removeNodes returns new instance, original unchanged")
        void epochStateRemoveNodes_immutable() {
            EpochState original = EpochState.fresh()
                    .markNodeCompleted("a")
                    .markNodeCompleted("b")
                    .markNodeCompleted("c");
            EpochState modified = original.removeNodes(Set.of("b", "c"));

            assertThat(original.getCompletedNodeIds()).containsExactlyInAnyOrder("a", "b", "c");
            assertThat(modified.getCompletedNodeIds()).containsExactly("a");
        }

        @Test
        @DisplayName("DagState.advanceEpoch returns new instance, original unchanged")
        void dagStateAdvanceEpoch_immutable() {
            DagState original = DagState.initial().advanceEpoch(1);
            DagState modified = original.advanceEpoch(2);

            assertThat(original.getCurrentEpoch()).isEqualTo(1);
            assertThat(original.getFireCount()).isEqualTo(1);

            assertThat(modified.getCurrentEpoch()).isEqualTo(2);
            assertThat(modified.getFireCount()).isEqualTo(2);
        }
    }

    // ========================================================================
    // Complex: 3-DAG Full Lifecycle with Rerun and Epoch Cycling
    // ========================================================================

    @Nested
    @DisplayName("Complex: 3-DAG Full Lifecycle")
    class ThreeDagFullLifecycle {

        @Test
        @DisplayName("Full 3-DAG lifecycle: execute all → rerun DAG 2 node → rerun DAG 3 trigger → interleave re-execution")
        void full3DagLifecycleWithReruns() {
            // === Phase 1: All triggers ready ===
            StateSnapshot s = build3DagSnapshot(
                    epoch(Set.of(), Set.of(T_WEBHOOK)),
                    epoch(Set.of(), Set.of(T_SCHEDULE)),
                    epoch(Set.of(), Set.of(T_MANUAL))
            );
            assertReady(s, T_WEBHOOK);
            assertReady(s, T_SCHEDULE);
            assertReady(s, T_MANUAL);

            // === Phase 2: Execute all DAGs to completion ===
            s = build3DagSnapshot(
                    epoch(Set.of(T_WEBHOOK, FETCH, TRANSFORM, STORE), Set.of()),
                    epoch(Set.of(T_SCHEDULE, CLEANUP, NOTIFY), Set.of()),
                    new EpochState(
                            Set.of(T_MANUAL, VALIDATE, DECISION, APPROVE),
                            Set.of(), Set.of(REJECT), Set.of(), Set.of(), Set.of(),
                            Map.of(DECISION, Set.of("if")),
                            Map.of(), Map.of(), null
                    )
            );
            s = withNodeCounts(s, T_WEBHOOK, FETCH, TRANSFORM, STORE);
            s = withNodeCounts(s, T_SCHEDULE, CLEANUP, NOTIFY);
            s = withNodeCounts(s, T_MANUAL, VALIDATE, DECISION, APPROVE);

            // Everything completed
            assertThat(s.getReadyNodeIds()).isEmpty();

            // === Phase 3: Rerun CLEANUP in DAG 2 (selective reset) ===
            s = s.resetDag(Set.of(CLEANUP, NOTIFY));
            assertCompleted(s, T_SCHEDULE); // trigger stays
            assertThat(s.getCompletedNodeIds()).doesNotContain(CLEANUP, NOTIFY);

            // Add cleanup to ready
            s = s.addReadyNode(T_SCHEDULE, CLEANUP);
            assertReady(s, CLEANUP);
            assertPending(s, NOTIFY);

            // DAG 1 and DAG 3: untouched
            assertCompleted(s, STORE);
            assertCompleted(s, APPROVE);

            // === Phase 4: Rerun DAG 3 trigger (new epoch) ===
            s = rebuildDag(s, T_MANUAL, epoch(Set.of(), Set.of(T_MANUAL)), 2);
            assertReady(s, T_MANUAL);
            assertPending(s, VALIDATE);

            // DAG 2 still has cleanup ready
            assertReady(s, CLEANUP);

            // === Phase 5: Interleave - execute cleanup (DAG 2), then manual trigger (DAG 3) ===
            // Execute cleanup (rebuildDag sets epoch state; incrementNodeCount tracks global count)
            s = rebuildDag(s, T_SCHEDULE,
                    epoch(Set.of(T_SCHEDULE, CLEANUP), Set.of(NOTIFY)), 1);
            s = s.incrementNodeCount(CLEANUP, "COMPLETED");
            assertCompleted(s, CLEANUP);
            assertReady(s, NOTIFY);
            assertThat(s.getNodes().get(CLEANUP).completed()).isEqualTo(2);

            // Execute manual trigger (DAG 3 epoch 2)
            s = rebuildDag(s, T_MANUAL,
                    epoch(Set.of(T_MANUAL), Set.of(VALIDATE)), 2);
            assertCompleted(s, T_MANUAL);
            assertReady(s, VALIDATE);

            // DAG 1: still fully completed
            assertCompleted(s, STORE);

            // === Phase 6: Complete remaining ===
            s = rebuildDag(s, T_SCHEDULE,
                    epoch(Set.of(T_SCHEDULE, CLEANUP, NOTIFY), Set.of()), 1);
            s = rebuildDag(s, T_MANUAL,
                    new EpochState(
                            Set.of(T_MANUAL, VALIDATE, DECISION, REJECT),
                            Set.of(), Set.of(APPROVE), Set.of(), Set.of(), Set.of(),
                            Map.of(DECISION, Set.of("else")),
                            Map.of(), Map.of(), null
                    ), 2);

            // All DAGs done
            assertCompleted(s, STORE);      // DAG 1
            assertCompleted(s, NOTIFY);     // DAG 2
            assertCompleted(s, REJECT);     // DAG 3 (now took ELSE branch)
            assertThat(s.getSkippedNodeIds()).contains(APPROVE); // APPROVE skipped in epoch 2
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private EpochState epoch(Set<String> completed, Set<String> ready) {
        return new EpochState(completed, Set.of(), Set.of(), Set.of(), ready, Set.of(),
                Map.of(), Map.of(), Map.of(), null);
    }

    private DagState dagWith(EpochState epochState, int epoch) {
        return new DagState(epoch, 0, 1, Map.of(epoch, epochState), Set.of(epoch));
    }

    private StateSnapshot buildSingleDagSnapshot(String triggerId, EpochState epochState, int epoch) {
        return createSnapshot(Map.of(triggerId, dagWith(epochState, epoch)), null, null);
    }

    private StateSnapshot build3DagSnapshot(EpochState dag1, EpochState dag2, EpochState dag3) {
        return createSnapshot(Map.of(
                T_WEBHOOK, dagWith(dag1, 1),
                T_SCHEDULE, dagWith(dag2, 1),
                T_MANUAL, dagWith(dag3, 1)
        ), null, null);
    }

    private StateSnapshot rebuildDag(StateSnapshot existing, String triggerId,
                                      EpochState epochState, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(existing.getDags());
        newDags.put(triggerId, dagWith(epochState, epoch));
        return createSnapshot(newDags, existing.getNodes(), existing.getEdges());
    }

    private StateSnapshot createSnapshot(Map<String, DagState> dags,
                                          Map<String, StateSnapshot.NodeCounts> nodes,
                                          Map<String, StateSnapshot.EdgeCounts> edges) {
        return new StateSnapshot(
                3, 0L, dags,
                null, null, null, null, null,
                nodes, edges,
                null, null, null, null,
                0L, null
        );
    }

    private StateSnapshot withNodeCounts(StateSnapshot s, String... nodeIds) {
        for (String nodeId : nodeIds) {
            s = s.incrementNodeCount(nodeId, "COMPLETED");
        }
        return s;
    }

    private StateSnapshot markNodeInEpoch(StateSnapshot s, String triggerId,
                                           String nodeId, String status) {
        if ("completed".equals(status)) {
            return s.markNodeCompleted(triggerId, nodeId);
        } else if ("failed".equals(status)) {
            return s.markNodeFailed(triggerId, nodeId);
        }
        return s;
    }

    // ========================================================================
    // Assertion Helpers
    // ========================================================================

    private void assertReady(StateSnapshot s, String nodeId) {
        assertThat(s.getReadyNodeIds())
                .as("Node %s should be READY", nodeId).contains(nodeId);
        assertThat(s.getCompletedNodeIds())
                .as("Node %s should NOT be COMPLETED when READY", nodeId).doesNotContain(nodeId);
    }

    private void assertCompleted(StateSnapshot s, String nodeId) {
        assertThat(s.getCompletedNodeIds())
                .as("Node %s should be COMPLETED", nodeId).contains(nodeId);
        assertThat(s.getReadyNodeIds())
                .as("Node %s should NOT be READY when COMPLETED", nodeId).doesNotContain(nodeId);
    }

    private void assertPending(StateSnapshot s, String nodeId) {
        assertThat(s.getCompletedNodeIds())
                .as("Node %s should NOT be COMPLETED", nodeId).doesNotContain(nodeId);
        assertThat(s.getReadyNodeIds())
                .as("Node %s should NOT be READY", nodeId).doesNotContain(nodeId);
        assertThat(s.getRunningNodeIds())
                .as("Node %s should NOT be RUNNING", nodeId).doesNotContain(nodeId);
        assertThat(s.getFailedNodeIds())
                .as("Node %s should NOT be FAILED", nodeId).doesNotContain(nodeId);
    }
}
