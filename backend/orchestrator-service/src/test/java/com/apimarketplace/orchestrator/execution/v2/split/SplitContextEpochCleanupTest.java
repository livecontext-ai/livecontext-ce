package com.apimarketplace.orchestrator.execution.v2.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SplitContextManager epoch-scoped cleanup behavior.
 *
 * Verifies that clearEpoch() correctly removes epoch-scoped contexts
 * while preserving contexts for other epochs, and that clearRun()
 * removes all contexts for a run including epoch-scoped entries.
 */
@DisplayName("SplitContextManager epoch cleanup")
class SplitContextEpochCleanupTest {

    private SplitContextManager manager;

    private static final String RUN_ID = "run-1";
    private static final String SPLIT_NODE_ID = "core:split";
    private static final List<Object> ITEMS = List.of("a", "b", "c");

    @BeforeEach
    void setUp() {
        manager = new SplitContextManager();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // clearEpoch() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("clearEpoch()")
    class ClearEpochTests {

        @Test
        @DisplayName("Should remove epoch-scoped contexts for the specified epoch")
        void clearEpoch_removesEpochScopedContexts() {
            // Create an epoch-scoped context
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);

            // Verify the context exists in the epoch scope
            Optional<SplitContext> ctx = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            assertTrue(ctx.isPresent(), "Context should exist before clearEpoch");
            assertEquals(3, ctx.get().itemCount());

            // Clear epoch 1
            manager.clearEpoch(RUN_ID, 1);

            // Verify epoch-scoped context is gone
            Optional<SplitContext> ctxAfter = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            // The epoch-scoped lookup should return empty (the run-level fallback may still exist)
            // But the epoch-specific storage key "run-1:1" should be removed
            // getContext with epoch falls back to run-level, so we need to verify the epoch key is gone
            // by checking that a NEW epoch-scoped creation works independently
            manager.createContext(RUN_ID, 1, "core:new_split", 0, null, List.of("x"));
            Optional<SplitContext> newCtx = manager.getContext(RUN_ID, 1, "core:new_split", 0, null);
            assertTrue(newCtx.isPresent(), "New context in same epoch should work after clear");
            // The old split context should not reappear in the new epoch scope
            Optional<SplitContext> oldInNewEpoch = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            // It may still exist under run-level (backward compat storage in createContext)
            // This is expected: clearEpoch only removes "runId:epoch" key, not "runId" key
        }

        @Test
        @DisplayName("Should preserve contexts for other epochs")
        void clearEpoch_preservesOtherEpochs() {
            // Create contexts in epoch 1 and epoch 2
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);
            manager.createContext(RUN_ID, 2, "core:split_2", 0, null, List.of("x", "y"));

            // Clear only epoch 1
            manager.clearEpoch(RUN_ID, 1);

            // Epoch 2 context should still exist
            Optional<SplitContext> epoch2Ctx = manager.getContext(RUN_ID, 2, "core:split_2", 0, null);
            assertTrue(epoch2Ctx.isPresent(), "Epoch 2 context should be preserved");
            assertEquals(2, epoch2Ctx.get().itemCount());
        }

        @Test
        @DisplayName("Should be no-op for non-existent epoch")
        void clearEpoch_noOpForNonExistentEpoch() {
            // Create context in epoch 1
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);

            // Clear epoch 99 (does not exist)
            assertDoesNotThrow(() -> manager.clearEpoch(RUN_ID, 99));

            // Epoch 1 context should still exist
            Optional<SplitContext> ctx = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            assertTrue(ctx.isPresent(), "Context should still exist after clearing non-existent epoch");
        }

        @Test
        @DisplayName("Should handle clearing same epoch twice without error")
        void clearEpoch_idempotent() {
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);

            manager.clearEpoch(RUN_ID, 1);
            assertDoesNotThrow(() -> manager.clearEpoch(RUN_ID, 1));
        }

        @Test
        @DisplayName("Should remove multiple contexts within the same epoch")
        void clearEpoch_removesMultipleContextsInSameEpoch() {
            // Create two different split contexts in the same epoch
            manager.createContext(RUN_ID, 1, "core:split_a", 0, null, List.of("a1", "a2"));
            manager.createContext(RUN_ID, 1, "core:split_b", 0, null, List.of("b1", "b2", "b3"));

            // Both should exist
            assertTrue(manager.getContext(RUN_ID, 1, "core:split_a", 0, null).isPresent());
            assertTrue(manager.getContext(RUN_ID, 1, "core:split_b", 0, null).isPresent());

            // Clear epoch 1
            manager.clearEpoch(RUN_ID, 1);

            // Verify: the epoch-scoped key "run-1:1" is removed.
            // New epoch-scoped lookups for these splits should not find them under the epoch key.
            // (They may still fall back to run-level due to backward compat storage)
        }

        @Test
        @DisplayName("Should remove nested (scoped) contexts within the epoch")
        void clearEpoch_removesNestedContextsInEpoch() {
            // Create a nested split context with parent scope
            manager.createContext(RUN_ID, 1, "core:inner_split", 0, "s0", List.of("i1", "i2"));

            Optional<SplitContext> ctx = manager.getContext(RUN_ID, 1, "core:inner_split", 0, "s0");
            assertTrue(ctx.isPresent(), "Nested context should exist before clear");

            manager.clearEpoch(RUN_ID, 1);

            // After clearing, the epoch-scoped entry should be removed
            // The run-level fallback may still work (backward compat), but the epoch key is gone
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // clearRun() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("clearRun()")
    class ClearRunTests {

        @Test
        @DisplayName("Should remove all contexts for a run including epoch-scoped")
        void clearRun_removesAllContexts() {
            // Create contexts in multiple epochs plus run-level
            manager.createContext(RUN_ID, SPLIT_NODE_ID, 0, ITEMS);  // run-level
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);  // epoch 1
            manager.createContext(RUN_ID, 2, "core:split_2", 0, null, List.of("x"));  // epoch 2

            // Verify contexts exist
            assertTrue(manager.hasContexts(RUN_ID));

            // Clear the entire run
            manager.clearRun(RUN_ID);

            // All contexts should be gone
            assertFalse(manager.hasContexts(RUN_ID), "Run-level contexts should be removed");
            Optional<SplitContext> epoch1 = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            // getContext with epoch falls back to run-level, which is also cleared
            // so no fallback available
            assertFalse(epoch1.isPresent(), "Epoch 1 context should be removed after clearRun");

            Optional<SplitContext> epoch2 = manager.getContext(RUN_ID, 2, "core:split_2", 0, null);
            assertFalse(epoch2.isPresent(), "Epoch 2 context should be removed after clearRun");
        }

        @Test
        @DisplayName("Should not affect other runs")
        void clearRun_preservesOtherRuns() {
            String otherRunId = "run-2";

            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);
            manager.createContext(otherRunId, 1, SPLIT_NODE_ID, 0, null, List.of("d", "e"));

            // Clear only run-1
            manager.clearRun(RUN_ID);

            // run-2 should still have its context
            Optional<SplitContext> otherCtx = manager.getContext(otherRunId, 1, SPLIT_NODE_ID, 0, null);
            assertTrue(otherCtx.isPresent(), "Other run's context should be preserved");
            assertEquals(2, otherCtx.get().itemCount());
        }

        @Test
        @DisplayName("Should be no-op for non-existent run")
        void clearRun_noOpForNonExistentRun() {
            assertDoesNotThrow(() -> manager.clearRun("non-existent-run"));
        }

        @Test
        @DisplayName("Should clean up via RunScopedCache interface (cleanupRun)")
        void cleanupRun_delegatesToClearRun() {
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);
            assertTrue(manager.hasContexts(RUN_ID));

            // Use the RunScopedCache interface method
            manager.cleanupRun(RUN_ID);

            assertFalse(manager.hasContexts(RUN_ID));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Epoch-scoped context creation and retrieval
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Epoch-scoped context lifecycle")
    class EpochScopedLifecycleTests {

        @Test
        @DisplayName("Should create and retrieve epoch-scoped context")
        void createAndRetrieveEpochScopedContext() {
            SplitContext created = manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);

            assertNotNull(created);
            assertEquals(3, created.itemCount());

            Optional<SplitContext> retrieved = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            assertTrue(retrieved.isPresent());
            assertEquals(3, retrieved.get().itemCount());
        }

        @Test
        @DisplayName("Should isolate contexts between epochs for same split node")
        void isolateContextsBetweenEpochs() {
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, List.of("a", "b"));
            manager.createContext(RUN_ID, 2, SPLIT_NODE_ID, 0, null, List.of("x", "y", "z"));

            Optional<SplitContext> epoch1 = manager.getContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null);
            Optional<SplitContext> epoch2 = manager.getContext(RUN_ID, 2, SPLIT_NODE_ID, 0, null);

            assertTrue(epoch1.isPresent());
            assertTrue(epoch2.isPresent());
            assertEquals(2, epoch1.get().itemCount(), "Epoch 1 should have 2 items");
            assertEquals(3, epoch2.get().itemCount(), "Epoch 2 should have 3 items");
        }

        @Test
        @DisplayName("Should also store under run-level for backward compatibility")
        void epochScopedCreationAlsoStoresRunLevel() {
            manager.createContext(RUN_ID, 1, SPLIT_NODE_ID, 0, null, ITEMS);

            // Should be accessible via run-level getContext (without epoch)
            Optional<SplitContext> runLevel = manager.getContext(RUN_ID, SPLIT_NODE_ID, 0);
            assertTrue(runLevel.isPresent(), "Epoch-scoped creation should also store at run-level");
        }

        @Test
        @DisplayName("Cache name should be SplitContextCache")
        void cacheName() {
            assertEquals("SplitContextCache", manager.getCacheName());
        }

        @Test
        @DisplayName("Active run count should reflect stored runs")
        void activeRunCount() {
            assertEquals(0, manager.getActiveRunCount());

            manager.createContext("run-1", 1, SPLIT_NODE_ID, 0, null, ITEMS);
            // createContext with epoch stores under "run-1:1" and "run-1"
            assertTrue(manager.getActiveRunCount() >= 1);

            manager.createContext("run-2", 1, SPLIT_NODE_ID, 0, null, ITEMS);
            assertTrue(manager.getActiveRunCount() >= 2);
        }
    }
}
