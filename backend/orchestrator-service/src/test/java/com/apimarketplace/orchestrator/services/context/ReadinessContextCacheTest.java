package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for ReadinessContextCache - Piste 4.
 *
 * <p>The cache is wired into V2StepByStepService.getReadyNodes() so a multi-trigger
 * DAG with N triggers doesn't pay N identical context-load DB calls. These tests pin
 * the cache contract: hit/miss/eviction/invalidation by runId.
 */
@DisplayName("ReadinessContextCache")
class ReadinessContextCacheTest {

    private ReadinessContextCache cache;

    @BeforeEach
    void setUp() {
        cache = new ReadinessContextCache();
    }

    @Nested
    @DisplayName("getOrLoad()")
    class GetOrLoad {

        @Test
        @DisplayName("First call invokes loader; second call returns cached instance (NO loader invocation)")
        void cacheHitSkipsLoader() {
            AtomicInteger loaderCalls = new AtomicInteger(0);
            ExecutionContext ctx = mock(ExecutionContext.class);
            String key = ReadinessContextCache.key("run1", 0, 0, 0, "trigger:start");

            ExecutionContext first = cache.getOrLoad(key, () -> {
                loaderCalls.incrementAndGet();
                return ctx;
            });
            ExecutionContext second = cache.getOrLoad(key, () -> {
                loaderCalls.incrementAndGet();
                return mock(ExecutionContext.class); // would be a DIFFERENT mock if loader fired
            });

            assertThat(loaderCalls.get()).isEqualTo(1);
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("Distinct keys (different epoch / spawn / itemIndex / triggerId) DO NOT share entries")
        void distinctKeysSeparateEntries() {
            ExecutionContext ctxA = mock(ExecutionContext.class);
            ExecutionContext ctxB = mock(ExecutionContext.class);

            ExecutionContext a = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a"),
                () -> ctxA);
            ExecutionContext b = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 0, "trigger:b"),
                () -> ctxB);

            assertThat(a).isNotSameAs(b);
            assertThat(a).isSameAs(ctxA);
            assertThat(b).isSameAs(ctxB);
        }

        @Test
        @DisplayName("Different itemIndex isolates entries - per-item readiness is not conflated")
        void itemIndexIsolatesEntries() {
            ExecutionContext c0 = mock(ExecutionContext.class);
            ExecutionContext c5 = mock(ExecutionContext.class);

            ExecutionContext at0 = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a"),
                () -> c0);
            ExecutionContext at5 = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 5, "trigger:a"),
                () -> c5);

            assertThat(at0).isSameAs(c0);
            assertThat(at5).isSameAs(c5);
        }

        @Test
        @DisplayName("Different spawn bypasses cache - rerun computations don't poison canonical entries")
        void spawnBumpIsolatesEntries() {
            ExecutionContext c0 = mock(ExecutionContext.class);
            ExecutionContext c1 = mock(ExecutionContext.class);

            ExecutionContext spawn0 = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a"),
                () -> c0);
            ExecutionContext spawn1 = cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 1, 0, "trigger:a"),
                () -> c1);

            assertThat(spawn0).isSameAs(c0);
            assertThat(spawn1).isSameAs(c1);
        }
    }

    @Nested
    @DisplayName("invalidateRun() / cleanupRun()")
    class Invalidation {

        @Test
        @DisplayName("Invalidates all entries for the given runId, leaving other runs intact")
        void invalidatesOnlyTargetRun() {
            ExecutionContext ctxA = mock(ExecutionContext.class);
            ExecutionContext ctxB = mock(ExecutionContext.class);

            String keyRun1 = ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a");
            String keyRun2 = ReadinessContextCache.key("run2", 0, 0, 0, "trigger:a");

            cache.getOrLoad(keyRun1, () -> ctxA);
            cache.getOrLoad(keyRun2, () -> ctxB);

            assertThat(cache.containsKey(keyRun1)).isTrue();
            assertThat(cache.containsKey(keyRun2)).isTrue();

            cache.invalidateRun("run1");

            assertThat(cache.containsKey(keyRun1)).isFalse();
            assertThat(cache.containsKey(keyRun2)).isTrue();
        }

        @Test
        @DisplayName("Next getOrLoad after invalidation triggers the loader again (fresh load)")
        void freshLoadAfterInvalidation() {
            AtomicInteger loaderCalls = new AtomicInteger(0);
            String key = ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a");

            cache.getOrLoad(key, () -> {
                loaderCalls.incrementAndGet();
                return mock(ExecutionContext.class);
            });
            cache.invalidateRun("run1");
            cache.getOrLoad(key, () -> {
                loaderCalls.incrementAndGet();
                return mock(ExecutionContext.class);
            });

            assertThat(loaderCalls.get())
                .as("Invalidation must force a fresh load - otherwise just-completed nodes "
                    + "would not surface in the next readiness traversal.")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("cleanupRun() delegates to invalidateRun() - RunScopedCache lifecycle contract")
        void cleanupRunIsAliasForInvalidate() {
            String key = ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a");
            cache.getOrLoad(key, () -> mock(ExecutionContext.class));
            assertThat(cache.containsKey(key)).isTrue();

            cache.cleanupRun("run1");

            assertThat(cache.containsKey(key)).isFalse();
        }

        @Test
        @DisplayName("Null runId is a tolerated no-op")
        void nullRunIdNoOp() {
            cache.getOrLoad(
                ReadinessContextCache.key("run1", 0, 0, 0, "trigger:a"),
                () -> mock(ExecutionContext.class));
            cache.invalidateRun(null);
            assertThat(cache.estimatedSize()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Multi-trigger amortization - Piste 4 contract")
    class SplitTraversalAmortization {

        @Test
        @DisplayName("getReadyNodes traversal over N triggers + N items pays N DB calls (not N*N)")
        void traversalDoesNotMultiplyLoads() {
            // Simulate a multi-trigger DAG with 3 triggers; each traversal of getReadyNodes
            // iterates all 3 triggers. WITH the cache, the second consecutive traversal sees
            // 3 cache hits (zero loader calls). WITHOUT cache, it would pay 3 more loads.
            AtomicInteger loaderCalls = new AtomicInteger(0);
            String runId = "run1";

            // Traversal #1
            for (String trigger : new String[]{"trigger:a", "trigger:b", "trigger:c"}) {
                cache.getOrLoad(
                    ReadinessContextCache.key(runId, 0, 0, 0, trigger),
                    () -> {
                        loaderCalls.incrementAndGet();
                        return mock(ExecutionContext.class);
                    });
            }

            // Traversal #2 - same keys → expect all 3 to be cache hits.
            for (String trigger : new String[]{"trigger:a", "trigger:b", "trigger:c"}) {
                cache.getOrLoad(
                    ReadinessContextCache.key(runId, 0, 0, 0, trigger),
                    () -> {
                        loaderCalls.incrementAndGet();
                        return mock(ExecutionContext.class);
                    });
            }

            assertThat(loaderCalls.get())
                .as("Two traversals of 3 triggers each should pay 3 loads (first traversal) "
                    + "+ 0 (second cache hits), not 6.")
                .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("RunScopedCache contract")
    class RunScopedCacheContract {

        @Test
        @DisplayName("getCacheName / getDomain / getCacheSize return meaningful values")
        void metadata() {
            assertThat(cache.getCacheName()).isEqualTo("ReadinessContextCache");
            assertThat(cache.getDomain()).isEqualTo(
                com.apimarketplace.orchestrator.services.cache.RunScopedCache.CacheDomain.STATE);
            assertThat(cache.getCacheSize()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("key() format is deterministic and joins all coordinates with |")
        void keyFormat() {
            String key = ReadinessContextCache.key("run-abc", 3, 1, 5, "trigger:webhook");
            assertThat(key).isEqualTo("run-abc|3|1|5|trigger:webhook");
        }

        @Test
        @DisplayName("key() uses '_' sentinel when triggerId is null (avoid 'null' literal)")
        void keyHandlesNullTrigger() {
            String key = ReadinessContextCache.key("run-abc", 0, 0, 0, null);
            assertThat(key).isEqualTo("run-abc|0|0|0|_");
        }
    }
}
