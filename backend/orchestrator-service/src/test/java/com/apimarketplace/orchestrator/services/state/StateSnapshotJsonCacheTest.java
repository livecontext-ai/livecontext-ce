package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StateSnapshotJsonCache} - A2 Phase 4 out-of-tx
 * Redis cache for serialized state_snapshot JSON.
 *
 * <p>Pins the 4 race contracts documented in the production class:
 * <ol>
 *   <li>Race 1 (concurrent put out-of-order) - atomic put-only-if-newer via Lua</li>
 *   <li>Race 2 (rollback drift) - caller responsibility, not testable here</li>
 *   <li>Race 3 (deploy boundary) - V178 backfill, not testable here</li>
 *   <li>Race 6 (Redis seq desync) - seq mismatch refused, drift can't survive RTT</li>
 * </ol>
 *
 * <p>Plus the fail-OPEN outage discipline (Redis exception → empty/false,
 * never throws) and the metric tagging contract for Prometheus.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StateSnapshotJsonCache - A2 Phase 4 Redis cache")
class StateSnapshotJsonCacheTest {

    @Mock private StringRedisTemplate redis;

    @Mock @SuppressWarnings("rawtypes")
    private HashOperations hashOps;

    private StateSnapshotJsonCache cache;
    private SimpleMeterRegistry meterRegistry;

    private static final String RUN_ID = "run-1";
    private static final String CACHE_KEY = "orchestrator:snapshot-cache:run-1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        meterRegistry = new SimpleMeterRegistry();
        WorkflowMetrics metrics = new WorkflowMetrics(meterRegistry);
        cache = new StateSnapshotJsonCache(redis, metrics);
    }

    private double counter(String outcome) {
        var c = meterRegistry.find("orchestrator_state_snapshot_cache_outcome_count")
                .tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    @Nested
    @DisplayName("getPayloadIfMatchesSeq - read path")
    class GetPayload {

        @Test
        @DisplayName("Returns cached payload when seq matches the SQL oracle (hit + outcome=hit)")
        @SuppressWarnings("unchecked")
        void hitWhenSeqMatches() {
            when(hashOps.multiGet(eq(CACHE_KEY), eq(List.of("seq", "payload"))))
                    .thenReturn(List.of("42", "{\"version\":3,\"seq\":42}"));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 42L);

            assertThat(result).contains("{\"version\":3,\"seq\":42}");
            assertThat(counter("hit")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Returns empty on seq mismatch - cache BEHIND SQL (peer-instance committed since our seq read)")
        @SuppressWarnings("unchecked")
        void missOnSeqMismatchCacheBehind() {
            when(hashOps.multiGet(eq(CACHE_KEY), anyList()))
                    .thenReturn(List.of("41", "{\"seq\":41}"));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 42L);

            assertThat(result).isEmpty();
            assertThat(counter("miss_seq_mismatch")).isEqualTo(1.0);
            assertThat(counter("hit")).isZero();
        }

        @Test
        @DisplayName("Returns empty on seq mismatch - cache AHEAD of SQL (defensive: never serve a payload tagged with a seq the SQL oracle has not advertised yet) - audit Opus B 2026-05-09 missing-direction coverage")
        @SuppressWarnings("unchecked")
        void missOnSeqMismatchCacheAhead() {
            // Reverse direction: cached.seq=43, expected (from SQL)=42.
            // This direction is currently unreachable in production (cache is
            // populated read-side AFTER the SQL seq is observed), but the
            // contract is "strict equality" and the reader rejects either drift
            // direction so the test pins the contract for any future change
            // (e.g. writer-side warmup) that could legitimately produce it.
            when(hashOps.multiGet(eq(CACHE_KEY), anyList()))
                    .thenReturn(List.of("43", "{\"seq\":43}"));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 42L);

            assertThat(result).isEmpty();
            assertThat(counter("miss_seq_mismatch")).isEqualTo(1.0);
            assertThat(counter("hit")).isZero();
        }

        @Test
        @DisplayName("Returns empty when cache empty (both fields null) - outcome=miss")
        @SuppressWarnings("unchecked")
        void missWhenEmpty() {
            when(hashOps.multiGet(eq(CACHE_KEY), anyList()))
                    .thenReturn(java.util.Arrays.asList(null, null));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 1L);

            assertThat(result).isEmpty();
            assertThat(counter("miss")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Returns empty + miss_corrupt outcome on unparseable cached seq (legacy / corrupted entry)")
        @SuppressWarnings("unchecked")
        void missOnCorruptSeq() {
            when(hashOps.multiGet(eq(CACHE_KEY), anyList()))
                    .thenReturn(List.of("not-a-number", "{}"));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 5L);

            assertThat(result).isEmpty();
            assertThat(counter("miss_corrupt")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Fail-OPEN: Redis exception is swallowed, returns empty + outcome=error_read")
        @SuppressWarnings("unchecked")
        void failOpenOnRedisError() {
            when(hashOps.multiGet(eq(CACHE_KEY), anyList()))
                    .thenThrow(new RuntimeException("Redis down"));

            Optional<String> result = cache.getPayloadIfMatchesSeq(RUN_ID, 1L);

            assertThat(result).isEmpty();
            assertThat(counter("error_read")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Returns empty for null runId without touching Redis")
        void nullRunIdShortCircuits() {
            assertThat(cache.getPayloadIfMatchesSeq(null, 1L)).isEmpty();
            verify(redis, never()).opsForHash();
        }
    }

    @Nested
    @DisplayName("putIfNewer - write path")
    class PutIfNewer {

        @Test
        @DisplayName("Applied when Lua returns 1 (new seq strictly greater than current) - outcome=put_applied")
        @SuppressWarnings("unchecked")
        void appliedOnNewerSeq() {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(1L);

            boolean applied = cache.putIfNewer(RUN_ID, 10L, "{\"seq\":10}");

            assertThat(applied).isTrue();
            assertThat(counter("put_applied")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Dropped when Lua returns 0 (current seq >= proposed) - Race-1 contract")
        @SuppressWarnings("unchecked")
        void droppedWhenStale() {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(0L);

            boolean applied = cache.putIfNewer(RUN_ID, 5L, "{\"seq\":5}");

            assertThat(applied).isFalse();
            assertThat(counter("put_dropped_stale")).isEqualTo(1.0);
            assertThat(counter("put_applied")).isZero();
        }

        @Test
        @DisplayName("Fail-OPEN: Redis exception swallowed, returns false + outcome=error_put")
        @SuppressWarnings("unchecked")
        void failOpenOnRedisError() {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));

            boolean applied = cache.putIfNewer(RUN_ID, 1L, "{}");

            assertThat(applied).isFalse();
            assertThat(counter("error_put")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Returns false for null runId / null payload without touching Redis")
        void nullArgsShortCircuit() {
            assertThat(cache.putIfNewer(null, 1L, "{}")).isFalse();
            assertThat(cache.putIfNewer(RUN_ID, 1L, null)).isFalse();
            verify(redis, never()).execute(any(RedisScript.class), anyList(),
                    anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("cleanupRun - RunScopedCache contract")
    class CleanupRun {

        @Test
        @DisplayName("DELs the run's cache key on cleanup")
        void deletesCacheKey() {
            cache.cleanupRun(RUN_ID);

            verify(redis).delete(CACHE_KEY);
        }

        @Test
        @DisplayName("Swallows Redis exception (cleanup is best-effort)")
        void swallowsExceptionOnCleanup() {
            org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                    .when(redis).delete(anyString());

            // Must not throw
            cache.cleanupRun(RUN_ID);
        }
    }
}
