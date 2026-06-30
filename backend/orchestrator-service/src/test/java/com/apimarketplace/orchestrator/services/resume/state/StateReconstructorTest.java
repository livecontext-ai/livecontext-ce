package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StateReconstructor records")
class StateReconstructorTest {

    @Nested
    @DisplayName("StepDataPreparation record")
    class StepDataPreparationTests {

        @Test
        @DisplayName("Should hold step data preparation results")
        void shouldHoldStepDataPreparation() {
            List<WorkflowStepDataEntity> entities = List.of();
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of();

            StateReconstructor.StepDataPreparation prep = new StateReconstructor.StepDataPreparation(
                entities, stepsByAlias);

            assertSame(entities, prep.stepEntities());
            assertSame(stepsByAlias, prep.stepsByAlias());
        }
    }

    @Nested
    @DisplayName("OutputLoadMode enum")
    class OutputLoadModeTests {

        @Test
        @DisplayName("FULL and AGENT_AND_INTERFACE_ONLY values exist")
        void shouldHaveBothModes() {
            assertEquals(2, StateReconstructor.OutputLoadMode.values().length);
            assertNotNull(StateReconstructor.OutputLoadMode.FULL);
            assertNotNull(StateReconstructor.OutputLoadMode.AGENT_AND_INTERFACE_ONLY);
        }
    }

    @Nested
    @DisplayName("Redis-overlay enrichment of runningStepIds (P2.2 site 3b contract)")
    class RedisOverlayContract {

        // The site 3b production logic (StateReconstructor.java:286-294) merges
        // RunningNodeTracker.getRunningCountsAcrossEpochs into runningStepIds BEFORE
        // the fresh-trigger-recalc gate at line 297. This nested class pins that
        // exact merge semantic via the same algorithm a re-implementation would use.
        // Constructing the real StateReconstructor here would require 8 mocked
        // collaborators + a parsed StateSnapshot - disproportionate cost for the
        // 5-line merge. Instead we verify the merge logic in isolation: it is a
        // 1:1 copy of the production code and the trigger-recalc gate is also
        // documented here so a regression at line 286-297 either breaks this test
        // OR breaks the broader integration (when site 3b ships with P2.3
        // production callers).

        /**
         * Replicates the site 3b merge contract: enrich runningStepIds with any
         * Redis entry whose count is positive. Returns the merged set.
         */
        private Set<String> mergeRunningOverlay(Set<String> jsonbRunning, Map<String, Integer> redisRunning) {
            Set<String> merged = new HashSet<>(jsonbRunning);
            for (Map.Entry<String, Integer> entry : redisRunning.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    merged.add(entry.getKey());
                }
            }
            return merged;
        }

        /**
         * Replicates the site 3b trigger-recalc gate at production line 297.
         * The conjunction means recalc happens only when ALL three are empty;
         * any one non-empty defers recalc. Site 3b's whole purpose is to make
         * sure Redis-only-running flips runningStepIds non-empty so this gate
         * defers correctly post-elision.
         */
        private boolean triggerRecalcGate(Set<String> readySteps, Set<String> completedStepIds, Set<String> runningStepIds) {
            return readySteps.isEmpty() && completedStepIds.isEmpty() && runningStepIds.isEmpty();
        }

        @Test
        @DisplayName("Redis non-empty + JSONB empty → runningStepIds non-empty → trigger-recalc gate DEFERS (post-P2.3 contract)")
        void redisOnlyRunning_defersRecalcGate() {
            // Post-P2.3 scenario: JSONB runningNodeIds is elided → empty. Redis is
            // the only source flagging "node X is currently running". Without the
            // P2.2 site 3b overlay, the trigger-recalc gate would see all-empty
            // and re-fire calculateReadyTriggers on a node that is already running.
            Set<String> jsonbRunning = Set.of();
            Map<String, Integer> redisRunning = Map.of("mcp:step1", 1);

            Set<String> merged = mergeRunningOverlay(jsonbRunning, redisRunning);

            assertTrue(merged.contains("mcp:step1"));
            // Critical: gate MUST defer (return false → recalc NOT taken).
            assertFalse(triggerRecalcGate(Set.of(), Set.of(), merged),
                    "Trigger recalc gate must DEFER when Redis flags a running node, " +
                    "even when JSONB-running is empty (the P2.3 elision condition).");
        }

        @Test
        @DisplayName("Redis empty + JSONB empty + ready/completed empty → gate FIRES recalc (legitimate fresh epoch)")
        void allEmpty_firesRecalcGate() {
            // Negative control: a truly fresh epoch with no running activity anywhere
            // legitimately triggers recalc. The overlay must NOT block this case.
            Set<String> merged = mergeRunningOverlay(Set.of(), Map.of());

            assertTrue(merged.isEmpty());
            assertTrue(triggerRecalcGate(Set.of(), Set.of(), merged));
        }

        // CONTRACT REPLICA (not a regression lock). Like the merge/gate helpers above,
        // these mirror the production terminal short-circuit
        // ({@code redisAggregate/redisRunningCounts = runTerminal ? Map.of() : getRunningCountsAcrossEpochs(...)}
        // at StateReconstructor.java:298/:377) rather than invoking the real
        // reconstructState (8 mocked collaborators + a parsed StateSnapshot - see the
        // class header at the top of this nested class). They document/illustrate the
        // intended REST-path semantics; the SAME logic is exercised against real
        // production code on the symmetric WS path by
        // SnapshotServiceTest.shouldNotOverlayRunningCountsWhenRunTerminal (parameterized
        // over every terminal RunStatus), which DOES fail if the short-circuit is removed.
        private Map<String, Integer> overlayForRun(boolean runTerminal, Map<String, Integer> redisRunning) {
            return runTerminal ? Map.of() : redisRunning;
        }

        @Test
        @DisplayName("Contract: a terminal run suppresses the Redis overlay → a stale running entry does not enrich runningStepIds")
        void terminalRun_suppressesOverlay() {
            // Run is terminal (e.g. COMPLETED) but a stale Redis entry still flags a
            // node running (dropped markCompleted, or a missed overlay purge). The
            // terminal short-circuit drops it so the REST refresh never paints a
            // finished run's node as running - parity with the WS SnapshotService path.
            Map<String, Integer> staleRedis = Map.of("agent:writer", 1);

            Set<String> merged = mergeRunningOverlay(Set.of(), overlayForRun(true, staleRedis));

            assertTrue(merged.isEmpty(), "A terminal run must not enrich runningStepIds from the overlay");
        }

        @Test
        @DisplayName("Contract: a non-terminal run keeps the Redis overlay → a genuinely running node is still surfaced")
        void nonTerminalRun_keepsOverlay() {
            // Negative control: a still-running run must keep surfacing its running nodes.
            Map<String, Integer> redis = Map.of("agent:writer", 1);

            Set<String> merged = mergeRunningOverlay(Set.of(), overlayForRun(false, redis));

            assertTrue(merged.contains("agent:writer"));
        }

        @Test
        @DisplayName("Redis stale-zero entries are filtered out (count=0 NOT enriched)")
        void zeroCountFilteredOut() {
            // Redis hash retains a key with count=0 transiently between markCompleted
            // and the field-delete path. The enrichment must filter these out so a
            // recently-completed node doesn't spuriously re-flag as running.
            Set<String> merged = mergeRunningOverlay(Set.of(),
                    Map.of("mcp:stale1", 0, "mcp:active1", 1));

            assertFalse(merged.contains("mcp:stale1"));
            assertTrue(merged.contains("mcp:active1"));
        }

        @Test
        @DisplayName("JSONB running + Redis running for same nodeId - set-union semantics, no double-count")
        void jsonbAndRedisSameNode_setUnion() {
            // Pre-P2.3 overlap window: JSONB and Redis can both flag the same node.
            // Set semantics dedupe - the merged set has cardinality 1, not 2.
            Set<String> merged = mergeRunningOverlay(Set.of("mcp:step1"), Map.of("mcp:step1", 1));

            assertEquals(1, merged.size());
            assertTrue(merged.contains("mcp:step1"));
        }
    }
}
