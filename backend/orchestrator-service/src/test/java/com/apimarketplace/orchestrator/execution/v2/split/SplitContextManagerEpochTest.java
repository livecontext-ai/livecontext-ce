package com.apimarketplace.orchestrator.execution.v2.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SplitContextManager - Epoch Isolation")
class SplitContextManagerEpochTest {

    private SplitContextManager manager;

    @BeforeEach
    void setUp() {
        manager = new SplitContextManager();
    }

    // ========================================================================
    // EPOCH-SCOPED CONTEXT CREATION
    // ========================================================================

    @Nested
    @DisplayName("createContext with epoch")
    class EpochScopedCreateTests {

        @Test
        @DisplayName("Should create context accessible via epoch-scoped key")
        void shouldCreateEpochScopedContext() {
            SplitContext ctx = manager.createContext("run1", 1, "core:split", 0, null,
                    List.of("a", "b", "c"));

            assertNotNull(ctx);
            assertEquals(3, ctx.itemCount());
        }

        @Test
        @DisplayName("Should isolate contexts across epochs")
        void shouldIsolateAcrossEpochs() {
            manager.createContext("run1", 1, "core:split", 0, null,
                    List.of("a", "b"));
            manager.createContext("run1", 2, "core:split", 0, null,
                    List.of("x", "y", "z"));

            Optional<SplitContext> ctx1 = manager.getContext("run1", 1, "core:split", 0, null);
            Optional<SplitContext> ctx2 = manager.getContext("run1", 2, "core:split", 0, null);

            assertTrue(ctx1.isPresent());
            assertTrue(ctx2.isPresent());
            assertEquals(2, ctx1.get().itemCount());
            assertEquals(3, ctx2.get().itemCount());
        }

        @Test
        @DisplayName("Should also store under runId for backward compatibility")
        void shouldAlsoStoreUnderRunId() {
            manager.createContext("run1", 1, "core:split", 0, null,
                    List.of("a", "b"));

            // Should be accessible via legacy (non-epoch) getContext
            Optional<SplitContext> legacy = manager.getContext("run1", "core:split", 0);
            assertTrue(legacy.isPresent());
            assertEquals(2, legacy.get().itemCount());
        }

        @Test
        @DisplayName("Should support parent scope key for nested splits")
        void shouldSupportParentScopeKey() {
            manager.createContext("run1", 1, "core:inner", 0, "s0",
                    List.of("nested_a"));
            manager.createContext("run1", 1, "core:inner", 0, "s1",
                    List.of("nested_b", "nested_c"));

            Optional<SplitContext> ctx0 = manager.getContext("run1", 1, "core:inner", 0, "s0");
            Optional<SplitContext> ctx1 = manager.getContext("run1", 1, "core:inner", 0, "s1");

            assertTrue(ctx0.isPresent());
            assertTrue(ctx1.isPresent());
            assertEquals(1, ctx0.get().itemCount());
            assertEquals(2, ctx1.get().itemCount());
        }
    }

    // ========================================================================
    // EPOCH-SCOPED GETCONTEXT
    // ========================================================================

    @Nested
    @DisplayName("getContext with epoch")
    class EpochScopedGetTests {

        @Test
        @DisplayName("Should return empty for non-existent epoch")
        void shouldReturnEmptyForNonExistentEpoch() {
            manager.createContext("run1", 1, "core:split", 0, null,
                    List.of("a"));

            Optional<SplitContext> ctx = manager.getContext("run1", 99, "core:split", 0, null);
            // Should fall back to run-level and find it
            assertTrue(ctx.isPresent());
        }

        @Test
        @DisplayName("Should prefer epoch-scoped over run-level")
        void shouldPreferEpochScopedOverRunLevel() {
            // Create via legacy (run-level)
            manager.createContext("run1", "core:split", 0, null, List.of("legacy"));

            // Create via epoch-scoped (should win on epoch-scoped lookup)
            manager.createContext("run1", 5, "core:split", 0, null,
                    List.of("epoch5_a", "epoch5_b"));

            // Epoch-scoped lookup for epoch 5
            Optional<SplitContext> ctx = manager.getContext("run1", 5, "core:split", 0, null);
            assertTrue(ctx.isPresent());
            assertEquals(2, ctx.get().itemCount()); // Should get the epoch-scoped one
        }

        @Test
        @DisplayName("Fallback to run-level when epoch not found")
        void shouldFallbackToRunLevel() {
            // Only create at run level
            manager.createContext("run1", "core:split", 0, null, List.of("run_level"));

            // Query with epoch that doesn't have its own context
            Optional<SplitContext> ctx = manager.getContext("run1", 42, "core:split", 0, null);
            assertTrue(ctx.isPresent());
            assertEquals(1, ctx.get().itemCount());
        }
    }

    // ========================================================================
    // CLEAR EPOCH
    // ========================================================================

    @Nested
    @DisplayName("clearEpoch()")
    class ClearEpochTests {

        @Test
        @DisplayName("Should remove epoch-scoped contexts only")
        void shouldRemoveEpochScopedOnly() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("e1"));
            manager.createContext("run1", 2, "core:split", 0, null, List.of("e2"));

            manager.clearEpoch("run1", 1);

            // Epoch 1's dedicated entry should be gone
            // But epoch 2 should still be accessible
            Optional<SplitContext> ctx2 = manager.getContext("run1", 2, "core:split", 0, null);
            assertTrue(ctx2.isPresent());
        }

        @Test
        @DisplayName("Should not affect other runs")
        void shouldNotAffectOtherRuns() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("r1"));
            manager.createContext("run2", 1, "core:split", 0, null, List.of("r2"));

            manager.clearEpoch("run1", 1);

            Optional<SplitContext> r2ctx = manager.getContext("run2", 1, "core:split", 0, null);
            assertTrue(r2ctx.isPresent());
        }

        @Test
        @DisplayName("Clearing non-existent epoch should be safe")
        void clearNonExistentShouldBeSafe() {
            assertDoesNotThrow(() -> manager.clearEpoch("run1", 999));
        }
    }

    // ========================================================================
    // CLEAR RUN
    // ========================================================================

    @Nested
    @DisplayName("clearRun()")
    class ClearRunTests {

        @Test
        @DisplayName("Should remove all contexts including epoch-scoped")
        void shouldRemoveAllIncludingEpochScoped() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("e1"));
            manager.createContext("run1", 2, "core:split", 0, null, List.of("e2"));
            manager.createContext("run1", "core:other", List.of("legacy"));

            manager.clearRun("run1");

            assertFalse(manager.hasContexts("run1"));
            // Epoch-scoped lookups should also find nothing
            Optional<SplitContext> ctx = manager.getContext("run1", 1, "core:split", 0, null);
            assertFalse(ctx.isPresent());
        }

        @Test
        @DisplayName("Should not affect other runs")
        void shouldNotAffectOtherRuns() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("r1"));
            manager.createContext("run2", 1, "core:split", 0, null, List.of("r2"));

            manager.clearRun("run1");

            assertTrue(manager.hasContexts("run2"));
        }
    }

    // ========================================================================
    // STATIC HELPERS
    // ========================================================================

    @Nested
    @DisplayName("Static helper methods")
    class StaticHelperTests {

        @Test
        @DisplayName("buildContextKey without parent scope")
        void buildKeyWithoutParent() {
            assertEquals("core:split:0", SplitContextManager.buildContextKey("core:split", 0));
            assertEquals("core:split:5", SplitContextManager.buildContextKey("core:split", 5));
        }

        @Test
        @DisplayName("buildContextKey with parent scope")
        void buildKeyWithParent() {
            assertEquals("core:inner:0/s0",
                    SplitContextManager.buildContextKey("core:inner", 0, "s0"));
            assertEquals("core:inner:0",
                    SplitContextManager.buildContextKey("core:inner", 0, null));
            assertEquals("core:inner:0",
                    SplitContextManager.buildContextKey("core:inner", 0, ""));
        }

        @Test
        @DisplayName("extractBaseSplitNodeId")
        void extractBase() {
            assertEquals("core:split", SplitContextManager.extractBaseSplitNodeId("core:split:0"));
            assertEquals("core:inner", SplitContextManager.extractBaseSplitNodeId("core:inner:0/s1"));
            assertNull(SplitContextManager.extractBaseSplitNodeId(null));
        }

        @Test
        @DisplayName("extractScopeSuffix")
        void extractSuffix() {
            assertNull(SplitContextManager.extractScopeSuffix("core:split:0"));
            assertEquals("s1", SplitContextManager.extractScopeSuffix("core:inner:0/s1"));
            assertNull(SplitContextManager.extractScopeSuffix(null));
        }
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Multiple splits in same epoch should be independent")
        void multipleSplitsInSameEpoch() {
            manager.createContext("run1", 1, "core:split_a", 0, null, List.of("a1", "a2"));
            manager.createContext("run1", 1, "core:split_b", 0, null, List.of("b1"));

            Optional<SplitContext> ctxA = manager.getContext("run1", 1, "core:split_a", 0, null);
            Optional<SplitContext> ctxB = manager.getContext("run1", 1, "core:split_b", 0, null);

            assertTrue(ctxA.isPresent());
            assertTrue(ctxB.isPresent());
            assertEquals(2, ctxA.get().itemCount());
            assertEquals(1, ctxB.get().itemCount());
        }

        @Test
        @DisplayName("Same split node across epochs should have independent contexts")
        void sameSplitAcrossEpochs() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("epoch1_items"));
            manager.createContext("run1", 2, "core:split", 0, null, List.of("e2_a", "e2_b"));
            manager.createContext("run1", 3, "core:split", 0, null, List.of("e3_a", "e3_b", "e3_c"));

            assertEquals(1, manager.getContext("run1", 1, "core:split", 0, null).get().itemCount());
            assertEquals(2, manager.getContext("run1", 2, "core:split", 0, null).get().itemCount());
            assertEquals(3, manager.getContext("run1", 3, "core:split", 0, null).get().itemCount());
        }

        @Test
        @DisplayName("clearEpoch should not affect the backward-compat run-level copy")
        void clearEpochShouldPreserveRunLevel() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("a"));

            manager.clearEpoch("run1", 1);

            // The run-level copy should still be accessible via legacy getContext
            Optional<SplitContext> legacy = manager.getContext("run1", "core:split", 0);
            assertTrue(legacy.isPresent());
        }

        @Test
        @DisplayName("RunScopedCache interface methods should work")
        void runScopedCacheInterfaceShouldWork() {
            manager.createContext("run1", 1, "core:split", 0, null, List.of("a"));

            assertEquals("SplitContextCache", manager.getCacheName());
            assertTrue(manager.getCacheSize() > 0);

            manager.cleanupRun("run1");
            assertFalse(manager.hasContexts("run1"));
        }
    }
}
