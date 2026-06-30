package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Multi-pod {@link RedisRunSeqBackend} - the root-cause fix for the run-page
 * freeze / node desync under {@code replicas: 2}.
 *
 * <p>The Lua INCR runs server-side, so these tests wire the mocked
 * {@link StringRedisTemplate} to a shared in-memory {@code store} that faithfully
 * reproduces the script's semantics (seed-floor, then INCR). Two backends pointed
 * at the SAME store simulate two orchestrator pods sharing one Redis - the only
 * way to assert the cross-pod monotonicity guarantee that the in-memory backend
 * cannot give.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisRunSeqBackend - shared cross-pod WS sequence")
class RedisRunSeqBackendTest {

    private static final String KEY = "orch:ws:seq:run-1";
    private static final long TTL_MS = 604_800_000L;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    /** Shared "Redis" - the single source of truth a real cluster would hold. */
    private final Map<String, Long> store = new ConcurrentHashMap<>();

    private RedisRunSeqBackend backend;

    private final ToLongFunction<String> zeroSeed = runId -> 0L;

    @BeforeEach
    void setUp() {
        wireFakeRedis(redis, valueOps, store);
        backend = new RedisRunSeqBackend(redis, TTL_MS);
    }

    /**
     * Wire a mocked StringRedisTemplate to {@code store}, reproducing the
     * {@code NEXT_SCRIPT} Lua: seed the key to the floor when absent or below the
     * floor, then INCR, then return the new value. {@code GET} and {@code DELETE}
     * read/mutate the same store.
     */
    private static void wireFakeRedis(StringRedisTemplate redis, ValueOperations<String, String> valueOps,
                                      Map<String, Long> store) {
        when(redis.opsForValue()).thenReturn(valueOps);

        when(redis.execute(any(RedisScript.class), anyList(), any(String.class), any(String.class)))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    List<String> keys = invocation.getArgument(1);
                    String key = keys.get(0);
                    long floor = Long.parseLong(invocation.getArgument(2).toString());
                    boolean wasAbsent = !store.containsKey(key);
                    if (script.getResultType() == List.class) {
                        // INCR_PRESENT: absent -> {0,1} and DO NOT create the key; present -> INCR, {v,0}.
                        if (wasAbsent) return List.of(0L, 1L);
                        store.compute(key, (k, cur) -> (cur < floor ? floor : cur) + 1L);
                        return List.of(store.get(key), 0L);
                    }
                    // SEED_INCR: atomically seed-floor then INCR; returns the new seq (Long).
                    store.compute(key, (k, cur) -> ((cur == null || cur < floor) ? floor : cur) + 1L);
                    return store.get(key);
                });

        when(valueOps.get(anyString())).thenAnswer(inv -> {
            Long v = store.get(inv.getArgument(0).toString());
            return v == null ? null : Long.toString(v);
        });

        when(redis.delete(anyString())).thenAnswer(inv ->
                store.remove(inv.getArgument(0).toString()) != null);
    }

    @Test
    @DisplayName("next delegates to the shared Redis counter and is strictly monotonic")
    void nextMonotonicViaRedis() {
        assertEquals(1L, backend.next("run-1", zeroSeed));
        assertEquals(2L, backend.next("run-1", zeroSeed));
        assertEquals(3L, backend.next("run-1", zeroSeed));
        assertEquals(3L, store.get(KEY), "shared Redis key holds the high-water");
    }

    @Test
    @DisplayName("REGRESSION: a pod seeded from a lagging DB high-water cannot regress the shared seq (the run-page freeze bug)")
    void crossPodSharedCounterNeverRegresses() {
        // Pod A drives the run forward on the shared Redis counter.
        RedisRunSeqBackend podA = new RedisRunSeqBackend(redis, TTL_MS);
        long a1 = podA.next("run-1", zeroSeed);
        long a2 = podA.next("run-1", zeroSeed);
        long a3 = podA.next("run-1", zeroSeed);
        assertEquals(3L, a3);

        // Pod B handles the next event (e.g. the user's Approve, or send_email),
        // cold for this run, seeded from the DB last_event_seq which the 5s flusher
        // has only advanced to 2 - the EXACT trigger of the old per-pod-AtomicLong bug.
        RedisRunSeqBackend podB = new RedisRunSeqBackend(redis, TTL_MS);
        ToLongFunction<String> laggingSeed = runId -> 2L;

        long b1 = podB.next("run-1", laggingSeed);

        // Pre-fix (per-pod AtomicLong): pod B would emit seed+1 = 3, colliding with /
        // regressing below pod A's 3 -> frontend strict-< drops it -> UI freezes.
        // Post-fix (shared Redis counter): pod B INCRs the live shared value -> 4.
        assertEquals(4L, b1, "shared counter must advance past pod A's high-water, never regress to it");
        assertTrue(b1 > a3, "no regression across pods");

        // And it keeps climbing globally regardless of which pod emits.
        assertEquals(5L, podA.next("run-1", zeroSeed));
        assertEquals(6L, podB.next("run-1", laggingSeed));
    }

    @Test
    @DisplayName("REGRESSION: key lost (TTL-expiry/eviction) + a pod holding a stale-LOW local floor re-seeds from the DB high-water, never below the FE seq")
    void keyLossReseedsFromDbHighWaterNotStaleLocal() {
        // Pod B processes one early event, then the run advances to H=10 entirely on pod A,
        // so pod B keeps a stale-LOW localHighWater (1) while the FE high-water is 10.
        RedisRunSeqBackend podA = new RedisRunSeqBackend(redis, TTL_MS);
        RedisRunSeqBackend podB = new RedisRunSeqBackend(redis, TTL_MS);

        podB.next("run-1", zeroSeed);                 // podB local = 1
        long feHighWater = 0;
        for (int i = 0; i < 9; i++) feHighWater = podA.next("run-1", zeroSeed);
        assertEquals(10L, feHighWater, "FE has seen seq up to 10");
        assertEquals(10L, store.get(KEY));

        // The shared key is LOST (7-day TTL expiry on a long-idle WAITING_TRIGGER run,
        // or eviction). The DB last_event_seq flusher had persisted the high-water = 10.
        store.remove(KEY);
        ToLongFunction<String> dbSeed10 = runId -> 10L;

        long resumed = podB.next("run-1", dbSeed10);

        // Pre-fix-B: podB floors at its stale local (1) -> emits 2 <= 10 -> FE strict-< drop
        // -> run page freezes. Post-fix-B: the script reports key-absent, podB reconciles
        // against the DB seed (10) and re-floors -> 11 > FE high-water.
        assertTrue(resumed > feHighWater,
                "after key loss, a stale-local pod must re-seed from the DB high-water, not regress below the FE seq (got " + resumed + ")");
        assertEquals(11L, resumed);
    }

    @Test
    @DisplayName("first next on a fresh key seeds from the DB high-water (cross-restart), not 1")
    void seedsFromDbHighWaterOnFreshKey() {
        ToLongFunction<String> seed500 = runId -> 500L;
        assertEquals(501L, backend.next("run-1", seed500));
        assertEquals(502L, backend.next("run-1", seed500));
    }

    @Test
    @DisplayName("Redis unavailable: next degrades to a local monotonic increment, never throws")
    void redisDownDegradesToLocalMonotonic() {
        reset(redis);
        when(redis.execute(any(RedisScript.class), anyList(), any(String.class), any(String.class)))
                .thenThrow(new RuntimeException("redis down"));

        long v1 = backend.next("run-1", zeroSeed);
        long v2 = backend.next("run-1", zeroSeed);
        long v3 = backend.next("run-1", zeroSeed);

        assertTrue(v1 >= 1L);
        assertTrue(v2 > v1, "degraded path must still be locally monotonic");
        assertTrue(v3 > v2);
    }

    @Test
    @DisplayName("current reads the shared value without bumping it (no INCR)")
    void currentReadsSharedValueNoBump() {
        store.put(KEY, 42L);
        assertEquals(42L, backend.current("run-1", zeroSeed));
        assertEquals(42L, store.get(KEY), "current() must not bump the shared counter");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(String.class), any(String.class));
    }

    @Test
    @DisplayName("current falls back to the DB seed when the key is absent and never bumped locally")
    void currentFallsBackToSeedWhenUnknown() {
        ToLongFunction<String> seed77 = runId -> 77L;
        assertEquals(77L, backend.current("run-unknown", seed77));
    }

    @Test
    @DisplayName("peek returns the local high-water only; remove deletes the shared key")
    void peekAndRemove() {
        assertTrue(backend.peek("run-1").isEmpty(), "no local value before first next");
        backend.next("run-1", zeroSeed);
        backend.next("run-1", zeroSeed);
        assertEquals(OptionalLong.of(2L), backend.peek("run-1"));

        backend.remove("run-1");
        assertFalse(store.containsKey(KEY), "remove must delete the shared Redis key");
        assertTrue(backend.peek("run-1").isEmpty());
        assertEquals(0, backend.size());
    }
}
