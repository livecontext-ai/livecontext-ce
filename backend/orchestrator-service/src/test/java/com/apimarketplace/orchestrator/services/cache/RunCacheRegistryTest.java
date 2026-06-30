package com.apimarketplace.orchestrator.services.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunCacheRegistry")
class RunCacheRegistryTest {

    private RunCacheRegistry registry;
    private TestCache cache1;
    private TestCache cache2;
    private TestCache cache3;

    @BeforeEach
    void setUp() {
        cache1 = new TestCache("Cache1", RunScopedCache.CacheDomain.EXECUTION, 10);
        cache2 = new TestCache("Cache2", RunScopedCache.CacheDomain.STREAMING, 5);
        cache3 = new TestCache("Cache3", RunScopedCache.CacheDomain.EXECUTION, 3);

        registry = new RunCacheRegistry(List.of(cache1, cache2, cache3));
    }

    @Nested
    @DisplayName("cleanupRun()")
    class CleanupRunTests {

        @Test
        @DisplayName("Should clean all registered caches")
        void shouldCleanAllCaches() {
            int cleaned = registry.cleanupRun("run-1");

            assertEquals(3, cleaned);
            assertTrue(cache1.wasCleanedFor("run-1"));
            assertTrue(cache2.wasCleanedFor("run-1"));
            assertTrue(cache3.wasCleanedFor("run-1"));
        }

        @Test
        @DisplayName("Should continue cleaning even if one fails")
        void shouldContinueOnFailure() {
            cache2.setThrowOnCleanup(true);

            int cleaned = registry.cleanupRun("run-1");

            // cache2 failed, but cache1 and cache3 still cleaned
            assertEquals(2, cleaned);
            assertTrue(cache1.wasCleanedFor("run-1"));
            assertTrue(cache3.wasCleanedFor("run-1"));
        }

        @Test
        @DisplayName("Should skip caches whose domain is in excludeDomains (SBS refire regression)")
        void shouldSkipExcludedDomains() {
            // SBS refire path: STREAMING domain (WsEventSequencer, SnapshotService) must
            // survive between fires to keep the seq counter monotonic. Purging mid-run
            // causes deferred fire #N publishes to collide with fire #N+1 seqs → FE
            // strict-< drops the events → UI freezes (2026-05-05 audit).
            int cleaned = registry.cleanupRun("run-1", Set.of(RunScopedCache.CacheDomain.STREAMING));

            // Only EXECUTION caches cleaned; STREAMING (cache2) skipped.
            assertEquals(2, cleaned);
            assertTrue(cache1.wasCleanedFor("run-1"));
            assertFalse(cache2.wasCleanedFor("run-1"),
                "STREAMING domain cache must NOT be purged when excluded");
            assertTrue(cache3.wasCleanedFor("run-1"));
        }

        @Test
        @DisplayName("Empty excludeDomains is equivalent to full purge")
        void emptyExcludeBehavesLikeFullPurge() {
            int cleaned = registry.cleanupRun("run-1", Set.of());

            assertEquals(3, cleaned);
            assertTrue(cache1.wasCleanedFor("run-1"));
            assertTrue(cache2.wasCleanedFor("run-1"));
            assertTrue(cache3.wasCleanedFor("run-1"));
        }
    }

    @Nested
    @DisplayName("getAllCaches()")
    class GetAllCachesTests {

        @Test
        @DisplayName("Should return defensive copy of all caches")
        void shouldReturnDefensiveCopy() {
            List<RunScopedCache> all = registry.getAllCaches();
            assertEquals(3, all.size());
        }
    }

    @Nested
    @DisplayName("getCachesByDomain()")
    class GetCachesByDomainTests {

        @Test
        @DisplayName("Should return caches by domain")
        void shouldReturnByDomain() {
            List<RunScopedCache> executionCaches = registry.getCachesByDomain(RunScopedCache.CacheDomain.EXECUTION);
            assertEquals(2, executionCaches.size());

            List<RunScopedCache> streamingCaches = registry.getCachesByDomain(RunScopedCache.CacheDomain.STREAMING);
            assertEquals(1, streamingCaches.size());
        }

        @Test
        @DisplayName("Should return empty list for unknown domain")
        void shouldReturnEmptyForUnknownDomain() {
            List<RunScopedCache> redisCaches = registry.getCachesByDomain(RunScopedCache.CacheDomain.REDIS);
            assertTrue(redisCaches.isEmpty());
        }
    }

    @Nested
    @DisplayName("getCacheCount()")
    class GetCacheCountTests {

        @Test
        @DisplayName("Should return total number of caches")
        void shouldReturnTotalCount() {
            assertEquals(3, registry.getCacheCount());
        }
    }

    @Nested
    @DisplayName("logCacheStatistics()")
    class LogCacheStatisticsTests {

        @Test
        @DisplayName("Should not throw when called")
        void shouldNotThrow() {
            assertDoesNotThrow(() -> registry.logCacheStatistics());
        }
    }

    @Nested
    @DisplayName("getTotalCacheEntries()")
    class GetTotalCacheEntriesTests {

        @Test
        @DisplayName("Should sum all trackable cache sizes")
        void shouldSumAllSizes() {
            assertEquals(18, registry.getTotalCacheEntries()); // 10 + 5 + 3
        }
    }

    // ==================== Test Double ====================

    private static class TestCache implements RunScopedCache {

        private final String name;
        private final CacheDomain domain;
        private final int size;
        private String lastCleanedRunId;
        private boolean throwOnCleanup = false;

        TestCache(String name, CacheDomain domain, int size) {
            this.name = name;
            this.domain = domain;
            this.size = size;
        }

        void setThrowOnCleanup(boolean throwOnCleanup) {
            this.throwOnCleanup = throwOnCleanup;
        }

        boolean wasCleanedFor(String runId) {
            return runId.equals(lastCleanedRunId);
        }

        @Override
        public void cleanupRun(String runId) {
            if (throwOnCleanup) {
                throw new RuntimeException("Simulated cleanup error");
            }
            this.lastCleanedRunId = runId;
        }

        @Override
        public String getCacheName() {
            return name;
        }

        @Override
        public CacheDomain getDomain() {
            return domain;
        }

        @Override
        public int getCacheSize() {
            return size;
        }
    }
}
