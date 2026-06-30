package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunningNodeTracker")
class RunningNodeTrackerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private SetOperations<String, String> setOps;

    private RunningNodeTracker tracker;
    private SimpleMeterRegistry meterRegistry;
    private WorkflowMetrics metrics;

    private static final String KEY_RUN1 = "orchestrator:running:run-1";
    private static final String KEY_RUN2 = "orchestrator:running:run-2";
    private static final String TRACKER_RUN1 = "orchestrator:running-epochs:run-1";

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        // The C3 fix (2026-05-09) adds a SET-tracker for epoch enumeration -
        // every per-epoch writer now SADDs into the tracker, every reader
        // SMEMBERS it before falling back to KEYS. Stub opsForSet globally
        // to avoid NPEs on writers that don't otherwise interact with the SET.
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkflowMetrics(meterRegistry);
        tracker = new RunningNodeTracker(redis, metrics);
    }

    @Nested
    @DisplayName("RunScopedCache interface")
    class CacheInterface {

        @Test
        @DisplayName("Should delete Redis key on cleanupRun")
        void shouldDeleteKeyOnCleanup() {
            tracker.cleanupRun("run-1");

            verify(redis).delete(KEY_RUN1);
        }

        @Test
        @DisplayName("Should return correct cache metadata")
        void shouldReturnCorrectCacheMetadata() {
            assertEquals("RunningNodeTracker", tracker.getCacheName());
            assertEquals(RunScopedCache.CacheDomain.STREAMING, tracker.getDomain());
            assertEquals(-1, tracker.getCacheSize());
        }
    }

    @Nested
    @DisplayName("Redis failure resilience")
    class RedisResilience {

        @Test
        @DisplayName("markRunning swallows Redis exceptions")
        void markRunningSwallowsException() {
            when(hashOps.increment(anyString(), anyString(), eq(1L)))
                .thenThrow(new RuntimeException("Redis down"));

            // Should not throw
            tracker.markRunning("run-1", 0, "mcp:step1");
        }

        @Test
        @DisplayName("markCompleted swallows Redis exceptions")
        void markCompletedSwallowsException() {
            when(hashOps.increment(anyString(), anyString(), eq(-1L)))
                .thenThrow(new RuntimeException("Redis down"));

            // Should not throw
            tracker.markCompleted("run-1", 0, "mcp:step1");
        }

        @Test
        @DisplayName("cleanupRun swallows Redis exceptions")
        void cleanupRunSwallowsException() {
            doThrow(new RuntimeException("Redis down")).when(redis).delete(anyString());

            // Should not throw
            tracker.cleanupRun("run-1");
        }
    }

    @Nested
    @DisplayName("Per-epoch overloads (P2.1.3)")
    class PerEpochOverloads {

        private static final String KEY_RUN1_E0 = "orchestrator:running:run-1:0";
        private static final String KEY_RUN1_E1 = "orchestrator:running:run-1:1";

        @Test
        @DisplayName("markRunning(runId, epoch, nodeId) writes to per-epoch hash key")
        void markRunningWritesPerEpochKey() {
            tracker.markRunning("run-1", 0, "mcp:step1");

            verify(hashOps).increment(KEY_RUN1_E0, "mcp:step1", 1);
            verify(redis).expire(eq(KEY_RUN1_E0), any());
        }

        @Test
        @DisplayName("markRunning isolates epochs - writes to E0 don't leak into E1")
        void markRunningIsolatesEpochs() {
            tracker.markRunning("run-1", 0, "mcp:step1");
            tracker.markRunning("run-1", 1, "mcp:step1");

            verify(hashOps).increment(KEY_RUN1_E0, "mcp:step1", 1);
            verify(hashOps).increment(KEY_RUN1_E1, "mcp:step1", 1);
        }

        @Test
        @DisplayName("setRunningCount per-epoch writes / deletes correctly")
        void setRunningCountPerEpoch() {
            tracker.setRunningCount("run-1", 0, "mcp:step1", 5);
            verify(hashOps).put(KEY_RUN1_E0, "mcp:step1", "5");

            tracker.setRunningCount("run-1", 0, "mcp:step1", 0);
            verify(hashOps).delete(KEY_RUN1_E0, "mcp:step1");
        }

        @Test
        @DisplayName("markCompleted per-epoch refreshes TTL before decrement (rev11 TTL fix preserved)")
        void markCompletedPerEpochRefreshesTtl() {
            when(hashOps.increment(KEY_RUN1_E0, "mcp:step1", -1)).thenReturn(2L);

            tracker.markCompleted("run-1", 0, "mcp:step1");

            verify(redis).expire(eq(KEY_RUN1_E0), any());
            verify(hashOps).increment(KEY_RUN1_E0, "mcp:step1", -1);
        }

        @Test
        @DisplayName("getRunningCounts(runId, epoch) reads per-epoch hash")
        void getRunningCountsPerEpoch() {
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put("mcp:step1", "3");
            when(hashOps.entries(KEY_RUN1_E0)).thenReturn(entries);

            Map<String, Integer> counts = tracker.getRunningCounts("run-1", 0);

            assertEquals(3, counts.get("mcp:step1"));
        }

        @Test
        @DisplayName("getRunningCounts per-epoch returns empty on Redis exception (fail-OPEN)")
        void getRunningCountsPerEpochFailOpen() {
            when(hashOps.entries(KEY_RUN1_E0)).thenThrow(new RuntimeException("Redis down"));

            assertTrue(tracker.getRunningCounts("run-1", 0).isEmpty());
        }
    }

    @Nested
    @DisplayName("getRunningCountsOrThrow - fail-CLOSED gate variant (P2.1.4)")
    class GetRunningCountsOrThrow {

        private static final String KEY_RUN1_E0 = "orchestrator:running:run-1:0";

        @Test
        @DisplayName("Returns counts when Redis is reachable")
        void returnsCountsOnSuccess() {
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put("mcp:step1", "1");
            when(hashOps.entries(KEY_RUN1_E0)).thenReturn(entries);

            Map<String, Integer> counts = tracker.getRunningCountsOrThrow("run-1", 0);

            assertEquals(1, counts.get("mcp:step1"));
        }

        @Test
        @DisplayName("Returns empty map when hash is empty (no exception)")
        void returnsEmptyOnEmptyHash() {
            when(hashOps.entries(KEY_RUN1_E0)).thenReturn(Map.of());

            assertTrue(tracker.getRunningCountsOrThrow("run-1", 0).isEmpty());
        }

        @Test
        @DisplayName("Throws RedisUnavailableException when Redis errors - gate-path caller MUST defer")
        void throwsOnRedisError() {
            when(hashOps.entries(KEY_RUN1_E0)).thenThrow(new RuntimeException("Redis down"));

            RedisUnavailableException ex = assertThrows(RedisUnavailableException.class,
                    () -> tracker.getRunningCountsOrThrow("run-1", 0));
            assertTrue(ex.getMessage().contains("orchestrator:running:run-1:0"));
        }
    }

    @Nested
    @DisplayName("getRunningCountsAcrossEpochs - SCAN aggregation + legacy fallback")
    class GetRunningCountsAcrossEpochs {

        private static final String KEY_LEGACY = "orchestrator:running:run-1";
        private static final String KEY_E0 = "orchestrator:running:run-1:0";
        private static final String KEY_E1 = "orchestrator:running:run-1:1";

        @Test
        @DisplayName("Aggregates per-epoch keys via SCAN and unions counts")
        void aggregatesAcrossEpochs() {
            when(redis.keys("orchestrator:running:run-1:*"))
                    .thenReturn(java.util.Set.of(KEY_E0, KEY_E1));
            Map<Object, Object> e0 = new LinkedHashMap<>();
            e0.put("mcp:step1", "2");
            Map<Object, Object> e1 = new LinkedHashMap<>();
            e1.put("mcp:step1", "1");
            e1.put("mcp:step2", "3");
            when(hashOps.entries(KEY_E0)).thenReturn(e0);
            when(hashOps.entries(KEY_E1)).thenReturn(e1);
            // Legacy flat-key path empty
            when(hashOps.entries(KEY_LEGACY)).thenReturn(Map.of());

            Map<String, Integer> merged = tracker.getRunningCountsAcrossEpochs("run-1");

            assertEquals(3, merged.get("mcp:step1"));  // 2 (E0) + 1 (E1)
            assertEquals(3, merged.get("mcp:step2"));  // only in E1
        }

        @Test
        @DisplayName("Legacy flat-key fallback merges into result for overlap-window in-flight runs")
        void legacyFallbackMergedIntoResult() {
            when(redis.keys("orchestrator:running:run-1:*")).thenReturn(java.util.Set.of());
            // Legacy flat key has data (in-flight run that started under pre-P2.1 code)
            Map<Object, Object> legacy = new LinkedHashMap<>();
            legacy.put("mcp:step1", "1");
            when(hashOps.entries(KEY_LEGACY)).thenReturn(legacy);

            Map<String, Integer> merged = tracker.getRunningCountsAcrossEpochs("run-1");

            assertEquals(1, merged.get("mcp:step1"));
        }

        @Test
        @DisplayName("Concurrent overlap - legacy AND per-epoch both populated for same nodeId - counts SUM")
        void overlapSamesNodeIdInBothShapes() {
            // Mid-deploy reality: a v1 replica wrote to the legacy flat key, a v2
            // replica wrote to the per-epoch key - same nodeId in both. The
            // aggregation contract per audit B: counts MUST sum, not de-dupe,
            // because both writes represent independent in-flight tracking signals.
            when(redis.keys("orchestrator:running:run-1:*"))
                    .thenReturn(java.util.Set.of(KEY_E0));
            Map<Object, Object> e0 = new LinkedHashMap<>();
            e0.put("mcp:step1", "1");  // per-epoch: 1 in-flight
            when(hashOps.entries(KEY_E0)).thenReturn(e0);
            Map<Object, Object> legacy = new LinkedHashMap<>();
            legacy.put("mcp:step1", "2");  // legacy: 2 in-flight
            when(hashOps.entries(KEY_LEGACY)).thenReturn(legacy);

            Map<String, Integer> merged = tracker.getRunningCountsAcrossEpochs("run-1");

            // Sum = 3. Critical: the gate consumer reads "is anything running?"
            // and a SUM ≥ 1 keeps the gate deferred; a sum of 0 would close the
            // epoch. De-duping legacy vs per-epoch would mask one of the writers.
            assertEquals(3, merged.get("mcp:step1"));
        }

        @Test
        @DisplayName("Returns empty when SCAN throws (fail-OPEN - same as flat overload)")
        void failOpenOnException() {
            when(redis.keys("orchestrator:running:run-1:*")).thenThrow(new RuntimeException("Redis down"));

            assertTrue(tracker.getRunningCountsAcrossEpochs("run-1").isEmpty());
        }
    }

    @Nested
    @DisplayName("cleanupRun - legacy flat key always evicted (P2.1.3 + C3 follow-up)")
    class CleanupRunPerEpoch {

        // The "deletesAllVariants" test that lived here moved into EpochSetTracker -
        // both fast-path (SMEMBERS) and drift-fallback (KEYS) are covered there
        // with the post-2026-05-09 List-based bulk-delete signature.

        @Test
        @DisplayName("Skips bulk-delete when both tracker SET and KEYS scan return empty (still clears legacy + tracker)")
        void skipsBulkDeleteWhenNothingToClean() {
            // Tracker empty (default) → drift fallback → KEYS empty → nothing to bulk-delete.
            when(redis.keys("orchestrator:running:run-1:*")).thenReturn(java.util.Set.of());

            tracker.cleanupRun("run-1");

            // No bulk delete on a Collection - only the tracker SET + legacy flat key.
            verify(redis, never()).delete(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any());
            verify(redis).delete("orchestrator:running-epochs:run-1");
            verify(redis).delete("orchestrator:running:run-1");
        }
    }

    @Nested
    @DisplayName("Epoch SET-tracker (C3 - anti redis.keys blocking, 2026-05-09)")
    class EpochSetTracker {

        private static final String KEY_E0 = "orchestrator:running:run-1:0";
        private static final String KEY_E1 = "orchestrator:running:run-1:1";
        private static final String KEY_LEGACY = "orchestrator:running:run-1";

        @Test
        @DisplayName("markRunning per-epoch SADDs the epoch into the tracker SET (regression: KEYS scan was the only enumeration path 2026-05-09)")
        void markRunningSaddsToTracker() {
            tracker.markRunning("run-1", 7, "mcp:step1");

            verify(setOps).add(TRACKER_RUN1, "7");
            verify(redis).expire(eq(TRACKER_RUN1), any());
        }

        @Test
        @DisplayName("setRunningCount with positive count SADDs the epoch into the tracker SET")
        void setRunningCountSaddsOnPositive() {
            tracker.setRunningCount("run-1", 3, "mcp:step1", 5);

            verify(setOps).add(TRACKER_RUN1, "3");
        }

        @Test
        @DisplayName("setRunningCount with zero count does NOT SADD (no per-epoch hash created)")
        void setRunningCountDoesNotSaddOnZero() {
            tracker.setRunningCount("run-1", 3, "mcp:step1", 0);

            verify(setOps, never()).add(eq(TRACKER_RUN1), anyString());
        }

        @Test
        @DisplayName("getRunningCountsAcrossEpochs reads from tracker SET via SMEMBERS - does NOT invoke redis.keys (regression: blocking KEYS was the hot-path bottleneck at >500 in-flight runs)")
        void readsViaTrackerSetFast() {
            when(setOps.members(TRACKER_RUN1))
                    .thenReturn(java.util.Set.of("0", "1"));
            Map<Object, Object> e0 = new LinkedHashMap<>();
            e0.put("mcp:step1", "2");
            Map<Object, Object> e1 = new LinkedHashMap<>();
            e1.put("mcp:step1", "1");
            e1.put("mcp:step2", "3");
            when(hashOps.entries(KEY_E0)).thenReturn(e0);
            when(hashOps.entries(KEY_E1)).thenReturn(e1);
            when(hashOps.entries(KEY_LEGACY)).thenReturn(Map.of());

            Map<String, Integer> merged = tracker.getRunningCountsAcrossEpochs("run-1");

            assertEquals(3, merged.get("mcp:step1"));
            assertEquals(3, merged.get("mcp:step2"));
            verify(redis, never()).keys(anyString());  // The whole point of C3.
        }

        @Test
        @DisplayName("Drift fallback: empty tracker SET triggers KEYS scan AND re-seeds the tracker so subsequent reads are fast")
        void driftFallbackReseedsTracker() {
            // Tracker SET empty (in-flight run that predated the fix, OR TTL expired).
            when(setOps.members(TRACKER_RUN1)).thenReturn(java.util.Set.of());
            // KEYS scan returns the per-epoch shape - fallback path must use it.
            when(redis.keys("orchestrator:running:run-1:*"))
                    .thenReturn(java.util.Set.of(KEY_E0, KEY_E1));
            when(hashOps.entries(KEY_E0)).thenReturn(Map.of("mcp:step1", "1"));
            when(hashOps.entries(KEY_E1)).thenReturn(Map.of("mcp:step2", "1"));
            when(hashOps.entries(KEY_LEGACY)).thenReturn(Map.of());

            Map<String, Integer> merged = tracker.getRunningCountsAcrossEpochs("run-1");

            assertEquals(1, merged.get("mcp:step1"));
            assertEquals(1, merged.get("mcp:step2"));
            // Tracker re-seeded so the next call short-circuits on SMEMBERS.
            verify(setOps).add(TRACKER_RUN1, "0");
            verify(setOps).add(TRACKER_RUN1, "1");
        }

        @Test
        @DisplayName("cleanupRun reads from tracker SET via SMEMBERS - does NOT invoke redis.keys")
        void cleanupViaTrackerSetFast() {
            when(setOps.members(TRACKER_RUN1)).thenReturn(java.util.Set.of("0", "1"));

            tracker.cleanupRun("run-1");

            // Bulk-delete the per-epoch hash keys derived from the tracker entries.
            // Order is unspecified - SMEMBERS returns an unordered Set; assert by
            // content to avoid coupling to HashSet iteration order.
            verify(redis).delete(org.mockito.ArgumentMatchers.<java.util.List<String>>argThat(list ->
                    list != null && list.size() == 2
                            && list.contains(KEY_E0) && list.contains(KEY_E1)));
            // Always evict the tracker SET itself + the legacy flat key.
            verify(redis).delete(TRACKER_RUN1);
            verify(redis).delete(KEY_LEGACY);
            verify(redis, never()).keys(anyString());
        }

        @Test
        @DisplayName("markCompleted prunes the tracker SREM when the per-epoch hash becomes empty (regression: ghost epochs polluted SMEMBERS, +1 RTT per SSE poll, audit B 2026-05-09)")
        void markCompletedSremTrackerWhenHashEmpty() {
            // Decrement to zero → field deleted → hash now empty (size returns 0)
            // → tracker SREM expected so the next SMEMBERS does not return this epoch.
            when(hashOps.increment(KEY_E0, "mcp:step1", -1L)).thenReturn(0L);
            when(hashOps.size(KEY_E0)).thenReturn(0L);

            tracker.markCompleted("run-1", 0, "mcp:step1");

            verify(setOps).remove(TRACKER_RUN1, "0");
        }

        @Test
        @DisplayName("markCompleted does NOT SREM the tracker when the per-epoch hash still has other fields")
        void markCompletedDoesNotSremWhenHashStillHasFields() {
            // Decrement to zero on this nodeId, but the hash still holds other peers.
            when(hashOps.increment(KEY_E0, "mcp:step1", -1L)).thenReturn(0L);
            when(hashOps.size(KEY_E0)).thenReturn(3L);  // 3 peer fields remain

            tracker.markCompleted("run-1", 0, "mcp:step1");

            verify(setOps, never()).remove(eq(TRACKER_RUN1), anyString());
        }

        @Test
        @DisplayName("markCompleted with positive remaining count does NOT touch the tracker SET")
        void markCompletedDoesNotSremWhenStillRunning() {
            when(hashOps.increment(KEY_E0, "mcp:step1", -1L)).thenReturn(2L);

            tracker.markCompleted("run-1", 0, "mcp:step1");

            verify(setOps, never()).remove(eq(TRACKER_RUN1), anyString());
            // And no SADD either - markCompleted never adds to the tracker.
            verify(setOps, never()).add(eq(TRACKER_RUN1), anyString());
        }

        @Test
        @DisplayName("Tracker SREM failure on hash-empty path is non-fatal - write_failure_count{operation=untrackepoch} increments")
        void trackerSremFailureIsNonFatal() {
            when(hashOps.increment(KEY_E0, "mcp:step1", -1L)).thenReturn(0L);
            when(hashOps.size(KEY_E0)).thenReturn(0L);
            doThrow(new RuntimeException("Redis SET down"))
                    .when(setOps).remove(eq(TRACKER_RUN1), anyString());

            // Must not throw - markCompleted's primary contract (decrement) succeeded.
            tracker.markCompleted("run-1", 0, "mcp:step1");

            var counter = meterRegistry.find("orchestrator_running_tracker_write_failure_count")
                    .tag("operation", "untrackepoch")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("cleanupRun drift fallback: empty tracker SET → KEYS scan → bulk delete + evict tracker")
        void cleanupRunDriftFallbackUsesKeysScan() {
            when(setOps.members(TRACKER_RUN1)).thenReturn(java.util.Set.of());
            java.util.Set<String> keys = java.util.Set.of(KEY_E0, KEY_E1);
            when(redis.keys("orchestrator:running:run-1:*")).thenReturn(keys);

            tracker.cleanupRun("run-1");

            // Drift path passes the raw KEYS Set as a List<String> for bulk DEL.
            // Order is unspecified - verify the underlying delete invocation occurred.
            verify(redis).delete(org.mockito.ArgumentMatchers.<java.util.List<String>>any());
            verify(redis).delete(TRACKER_RUN1);
            verify(redis).delete(KEY_LEGACY);
        }

        @Test
        @DisplayName("Tracker SADD failure is non-fatal - write proceeds, write_failure_count{operation=trackepoch} increments")
        void trackerSaddFailureIsNonFatal() {
            doThrow(new RuntimeException("Redis SET down"))
                    .when(setOps).add(eq(TRACKER_RUN1), anyString());

            // Must not throw - the per-epoch hash write is the primary contract.
            tracker.markRunning("run-1", 0, "mcp:step1");

            // The hash write happened.
            verify(hashOps).increment("orchestrator:running:run-1:0", "mcp:step1", 1);
            // The tracker failure is recorded.
            var counter = meterRegistry.find("orchestrator_running_tracker_write_failure_count")
                    .tag("operation", "trackepoch")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }
    }

    @Nested
    @DisplayName("Write-failure metric (P2.1.2)")
    class WriteFailureMetric {

        private double counterValue(String operation) {
            var counter = meterRegistry.find("orchestrator_running_tracker_write_failure_count")
                    .tag("operation", operation)
                    .counter();
            return counter == null ? 0.0 : counter.count();
        }

        @Test
        @DisplayName("markRunning fail-open path increments write_failure_count{operation=markrunning}")
        void markRunningIncrementsMetricOnFailure() {
            when(hashOps.increment(anyString(), anyString(), eq(1L)))
                .thenThrow(new RuntimeException("Redis down"));

            tracker.markRunning("run-1", 0, "mcp:step1");

            assertEquals(1.0, counterValue("markrunning"));
        }

        @Test
        @DisplayName("setRunningCount fail-open path increments write_failure_count{operation=setrunningcount}")
        void setRunningCountIncrementsMetricOnFailure() {
            doThrow(new RuntimeException("Redis down")).when(hashOps).put(anyString(), anyString(), anyString());

            tracker.setRunningCount("run-1", 0, "mcp:step1", 5);

            assertEquals(1.0, counterValue("setrunningcount"));
        }

        @Test
        @DisplayName("markCompleted fail-open path increments write_failure_count{operation=markcompleted}")
        void markCompletedIncrementsMetricOnFailure() {
            when(hashOps.increment(anyString(), anyString(), eq(-1L)))
                .thenThrow(new RuntimeException("Redis down"));

            tracker.markCompleted("run-1", 0, "mcp:step1");

            assertEquals(1.0, counterValue("markcompleted"));
        }

        @Test
        @DisplayName("Successful operations do NOT increment write_failure_count")
        void successfulOpsDoNotIncrementMetric() {
            tracker.markRunning("run-1", 0, "mcp:step1");
            when(hashOps.increment("orchestrator:running:run-1:0", "mcp:step1", -1)).thenReturn(0L);
            tracker.markCompleted("run-1", 0, "mcp:step1");

            assertEquals(0.0, counterValue("markrunning"));
            assertEquals(0.0, counterValue("markcompleted"));
        }
    }
}
