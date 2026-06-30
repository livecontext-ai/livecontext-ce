package com.apimarketplace.orchestrator.execution.v2.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BackEdgeState.
 *
 * <p>Semantic contract pinned by these tests: {@code state.iteration()} is the
 * body iteration that has just completed. The LoopNode initial body entry is
 * iteration 0, so {@link BackEdgeState#create(String, int, String)} returns
 * {@code iteration=0}. Subsequent body re-entries (driven by BackEdgeHandler)
 * advance to 1, 2, … via {@link BackEdgeState#incrementIteration()}.
 *
 * <p>{@link BackEdgeState#shouldContinue()} answers "can the loop fire one MORE
 * body run?" - i.e., {@code (iteration + 1) < maxIterations} - which is why the
 * boundary cases below check the next-iteration-fits condition rather than the
 * current-iteration-fits one.
 */
@DisplayName("BackEdgeState")
class BackEdgeStateTest {

    // ========================================================================
    // create() Tests
    // ========================================================================

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Should create initial state at iteration 0 (LoopNode initial body completed = iter 0)")
        void shouldCreateInitialStateAtIterationZero() {
            BackEdgeState state = BackEdgeState.create("mcp:a->mcp:b", 10, "#{true}");

            assertEquals("mcp:a->mcp:b", state.edgeId());
            assertEquals(0, state.iteration());
            assertEquals(10, state.maxIterations());
            assertEquals("#{true}", state.condition());
            assertFalse(state.terminated());
        }

        @Test
        @DisplayName("Should create state with specified max iterations")
        void shouldCreateStateWithSpecifiedMaxIterations() {
            BackEdgeState state = BackEdgeState.create("edge-1", 100, "");

            assertEquals(100, state.maxIterations());
        }

        @Test
        @DisplayName("Should create state with null condition")
        void shouldCreateStateWithNullCondition() {
            BackEdgeState state = BackEdgeState.create("edge-1", 5, null);

            assertNull(state.condition());
        }
    }

    // ========================================================================
    // incrementIteration() Tests
    // ========================================================================

    @Nested
    @DisplayName("incrementIteration()")
    class IncrementIterationTests {

        @Test
        @DisplayName("Should increment iteration count from 0 to 1")
        void shouldIncrementIterationCount() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "");

            BackEdgeState newState = state.incrementIteration();

            assertEquals(0, state.iteration()); // Original unchanged (starts at 0)
            assertEquals(1, newState.iteration());
        }

        @Test
        @DisplayName("Should preserve maxIterations when incrementing")
        void shouldPreserveMaxIterationsWhenIncrementing() {
            BackEdgeState state = BackEdgeState.create("edge-1", 50, "#{condition}");

            BackEdgeState newState = state.incrementIteration();

            assertEquals(50, newState.maxIterations());
        }

        @Test
        @DisplayName("Should preserve edgeId when incrementing")
        void shouldPreserveEdgeIdWhenIncrementing() {
            BackEdgeState state = BackEdgeState.create("mcp:source->mcp:target", 10, "");

            BackEdgeState newState = state.incrementIteration();

            assertEquals("mcp:source->mcp:target", newState.edgeId());
        }

        @Test
        @DisplayName("Should preserve condition when incrementing")
        void shouldPreserveConditionWhenIncrementing() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "#{result > 0}");

            BackEdgeState newState = state.incrementIteration();

            assertEquals("#{result > 0}", newState.condition());
        }

        @Test
        @DisplayName("Should preserve terminated flag when incrementing")
        void shouldPreserveTerminatedFlagWhenIncrementing() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "");

            BackEdgeState newState = state.incrementIteration();

            assertFalse(newState.terminated());
        }

        @Test
        @DisplayName("Should allow multiple increments producing 0,1,2,3 sequence")
        void shouldAllowMultipleIncrements() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "");

            BackEdgeState state1 = state.incrementIteration();
            BackEdgeState state2 = state1.incrementIteration();
            BackEdgeState state3 = state2.incrementIteration();

            assertEquals(0, state.iteration());  // create() starts at 0
            assertEquals(1, state1.iteration());
            assertEquals(2, state2.iteration());
            assertEquals(3, state3.iteration());
        }
    }

    // ========================================================================
    // terminate() Tests
    // ========================================================================

    @Nested
    @DisplayName("terminate()")
    class TerminateTests {

        @Test
        @DisplayName("Should mark state as terminated")
        void shouldMarkStateAsTerminated() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "");

            BackEdgeState newState = state.terminate();

            assertFalse(state.terminated()); // Original unchanged
            assertTrue(newState.terminated());
        }

        @Test
        @DisplayName("Should preserve iteration count when terminating")
        void shouldPreserveIterationCountWhenTerminating() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "")
                    .incrementIteration()
                    .incrementIteration();

            BackEdgeState terminatedState = state.terminate();

            assertEquals(2, terminatedState.iteration()); // create(0) + 2 increments = 2
        }

        @Test
        @DisplayName("Should preserve edgeId when terminating")
        void shouldPreserveEdgeIdWhenTerminating() {
            BackEdgeState state = BackEdgeState.create("mcp:a->mcp:b", 10, "");

            BackEdgeState terminatedState = state.terminate();

            assertEquals("mcp:a->mcp:b", terminatedState.edgeId());
        }

        @Test
        @DisplayName("Should preserve condition when terminating")
        void shouldPreserveConditionWhenTerminating() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "#{x > 0}");

            BackEdgeState terminatedState = state.terminate();

            assertEquals("#{x > 0}", terminatedState.condition());
        }
    }

    // ========================================================================
    // shouldContinue() Tests
    // ========================================================================

    @Nested
    @DisplayName("shouldContinue()")
    class ShouldContinueTests {

        @Test
        @DisplayName("Should return true when not terminated and next iteration fits")
        void shouldReturnTrueWhenNotTerminatedAndNextIterFits() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "");

            // iteration=0, max=10 → next iter would be 1, 1<10 = true
            assertTrue(state.shouldContinue());
        }

        @Test
        @DisplayName("Should return false when terminated")
        void shouldReturnFalseWhenTerminated() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "").terminate();

            assertFalse(state.shouldContinue());
        }

        @Test
        @DisplayName("Should return false when next iteration would equal maxIterations")
        void shouldReturnFalseWhenNextIterationEqualsMax() {
            // After 1 increment from create(0), iteration=1. With max=2, next iter
            // would be 2 - does NOT fit (2<2 is false).
            BackEdgeState state = BackEdgeState.create("edge-1", 2, "")
                    .incrementIteration();

            assertFalse(state.shouldContinue());
        }

        @Test
        @DisplayName("Should toggle false right at the boundary")
        void shouldToggleFalseAtBoundary() {
            // max=3, iteration=0 → next iter=1, 1<3 = true (1st iter would fit)
            BackEdgeState s0 = BackEdgeState.create("edge-1", 3, "");
            assertTrue(s0.shouldContinue());

            // iteration=1 → next iter=2, 2<3 = true (2nd iter would fit)
            BackEdgeState s1 = s0.incrementIteration();
            assertTrue(s1.shouldContinue());

            // iteration=2 → next iter=3, 3<3 = false (no more iters fit)
            BackEdgeState s2 = s1.incrementIteration();
            assertFalse(s2.shouldContinue());
        }

        @Test
        @DisplayName("Should return false when maxIterations is 0")
        void shouldReturnFalseWhenMaxIterationsZero() {
            // iteration=0, max=0 → next iter=1, 1<0 = false. Loop body never runs
            // again (LoopNode itself short-circuits to exit when max=0, but the
            // contract here is that no further back-edge admits.)
            BackEdgeState state = BackEdgeState.create("edge-1", 0, "");

            assertFalse(state.shouldContinue());
        }

        @Test
        @DisplayName("Should return false when maxIterations is 1 (only the LoopNode initial body fits)")
        void shouldReturnFalseWhenMaxIterationsOne() {
            // iteration=0 (LoopNode initial body completed), max=1 → next iter=1,
            // 1<1 = false. Only one body run total (the LoopNode initial entry).
            BackEdgeState state = BackEdgeState.create("edge-1", 1, "");

            assertFalse(state.shouldContinue());
        }

        @Test
        @DisplayName("Should return true when maxIterations is 2 and only the initial body has run")
        void shouldReturnTrueWhenMaxIterationsTwoAndInitialBodyDone() {
            // iteration=0, max=2 → next iter=1, 1<2 = true. The 2nd body iter (1)
            // is admitted by the back-edge.
            BackEdgeState state = BackEdgeState.create("edge-1", 2, "");

            assertTrue(state.shouldContinue());
        }
    }

    // ========================================================================
    // Immutability Tests
    // ========================================================================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("incrementIteration should not modify original state")
        void incrementIterationShouldNotModifyOriginal() {
            BackEdgeState original = BackEdgeState.create("edge-1", 10, "");
            int originalIteration = original.iteration();

            original.incrementIteration();

            assertEquals(originalIteration, original.iteration());
        }

        @Test
        @DisplayName("terminate should not modify original state")
        void terminateShouldNotModifyOriginal() {
            BackEdgeState original = BackEdgeState.create("edge-1", 10, "");

            original.terminate();

            assertFalse(original.terminated());
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle large max iterations")
        void shouldHandleLargeMaxIterations() {
            BackEdgeState state = BackEdgeState.create("edge-1", Integer.MAX_VALUE, "");

            assertEquals(Integer.MAX_VALUE, state.maxIterations());
            assertTrue(state.shouldContinue());
        }

        @Test
        @DisplayName("Should handle chained operations")
        void shouldHandleChainedOperations() {
            BackEdgeState state = BackEdgeState.create("edge-1", 100, "#{counter < 5}")
                    .incrementIteration()
                    .incrementIteration()
                    .incrementIteration();

            assertEquals(3, state.iteration()); // create(0) + 3 increments = 3
            assertEquals("#{counter < 5}", state.condition());
            assertTrue(state.shouldContinue()); // next iter=4, 4<100 = true
        }

        @Test
        @DisplayName("Should handle empty edgeId")
        void shouldHandleEmptyEdgeId() {
            BackEdgeState state = BackEdgeState.create("", 10, "");

            assertEquals("", state.edgeId());
        }

        @Test
        @DisplayName("Terminated state with increments should still be terminated")
        void terminatedStateShouldStayTerminated() {
            BackEdgeState state = BackEdgeState.create("edge-1", 10, "")
                    .incrementIteration()
                    .terminate();

            // After terminate, shouldContinue is false regardless of iteration count
            assertFalse(state.shouldContinue());
            assertEquals(1, state.iteration()); // create(0) + 1 increment = 1
            assertTrue(state.terminated());
        }
    }
}
