package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis exercise of {@link RedisRunSeqBackend} against an actual Redis, so the
 * ACTUAL Lua {@code NEXT_SCRIPT} runs end-to-end (the mocked-Redis unit test re-models
 * the script in Java and therefore cannot catch a transcription / {@code <} vs
 * {@code <=} / type-coercion error in the real string). Two backends pointed at the
 * SAME container are two real orchestrator pods sharing one Redis - the genuine
 * 10-replica setup.
 *
 * <p>Skips gracefully when no Docker daemon is available (CI without Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisRunSeqBackend - REAL Redis (actual Lua)")
class RedisRunSeqBackendRealRedisTest {

    private static final long TTL_MS = 604_800_000L;
    private static final String RUN = "run-real-1";
    private static final String KEY = "orch:ws:seq:" + RUN;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory cf;
    private StringRedisTemplate redis;

    private final ToLongFunction<String> zeroSeed = runId -> 0L;

    @BeforeEach
    void setUp() {
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
        redis.delete(KEY); // isolate each test
    }

    @AfterEach
    void tearDown() {
        if (redis != null) redis.delete(KEY);
        if (cf != null) cf.destroy();
    }

    private RedisRunSeqBackend backend() {
        return new RedisRunSeqBackend(redis, TTL_MS);
    }

    @Test
    @DisplayName("fresh key: real Lua seeds the floor, INCRs, and sets a positive TTL")
    void freshKeySeedsIncrementsAndSetsTtl() {
        RedisRunSeqBackend backend = backend();
        assertEquals(1L, backend.next(RUN, zeroSeed));
        assertEquals(2L, backend.next(RUN, zeroSeed));
        assertEquals(3L, backend.next(RUN, zeroSeed));
        assertEquals("3", redis.opsForValue().get(KEY));

        Long ttl = redis.getExpire(KEY, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "PEXPIRE must arm a positive TTL on every event (got " + ttl + ")");
    }

    @Test
    @DisplayName("first next floors at the DB high-water seed (cross-restart), not 1")
    void seedsFromDbHighWater() {
        assertEquals(501L, backend().next(RUN, runId -> 500L));
    }

    @Test
    @DisplayName("two real pods on the SAME Redis: a pod seeded from a lagging value cannot regress the shared seq")
    void crossPodSharedNoRegress() {
        RedisRunSeqBackend podA = backend();
        RedisRunSeqBackend podB = backend();

        assertEquals(1L, podA.next(RUN, zeroSeed));
        assertEquals(2L, podA.next(RUN, zeroSeed));
        assertEquals(3L, podA.next(RUN, zeroSeed));

        // Pod B is cold for this run and seeds from a lagging DB value (2) - the old bug's
        // trigger. The shared real INCR must hand it 4, never 3.
        assertEquals(4L, podB.next(RUN, runId -> 2L));
        assertEquals(5L, podA.next(RUN, zeroSeed));
    }

    @Test
    @DisplayName("REAL-LUA key loss: a pod with a stale-low local floor re-seeds from the DB high-water, never below the FE seq")
    void keyLossReseedsFromDbHighWater() {
        RedisRunSeqBackend podA = backend();
        RedisRunSeqBackend podB = backend();

        podB.next(RUN, zeroSeed);                       // podB local high-water = 1
        long feHighWater = 0;
        for (int i = 0; i < 9; i++) feHighWater = podA.next(RUN, zeroSeed);
        assertEquals(10L, feHighWater);

        redis.delete(KEY);                              // TTL-expiry / eviction of the shared key
        long resumed = podB.next(RUN, runId -> 10L);    // DB last_event_seq flushed = 10

        assertTrue(resumed > feHighWater,
                "stale-local pod must re-seed from DB high-water after key loss, not regress (got " + resumed + ")");
        assertEquals(11L, resumed);
    }

    @Test
    @DisplayName("current reads the shared value without bumping; remove deletes the key")
    void currentAndRemove() {
        RedisRunSeqBackend backend = backend();
        backend.next(RUN, zeroSeed);
        backend.next(RUN, zeroSeed);

        assertEquals(2L, backend.current(RUN, zeroSeed));
        assertEquals("2", redis.opsForValue().get(KEY), "current() must not bump");

        backend.remove(RUN);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(KEY)), "remove must delete the shared key");
        assertEquals(77L, backend.current(RUN, runId -> 77L), "current falls back to DB seed once the key is gone");
    }

    @Test
    @DisplayName("REAL-LUA concurrent key loss: many stale-low pods reseeding a deleted key never regress below the FE high-water nor collide")
    void concurrentKeyLossNoRegressionBelowHighWater() throws InterruptedException {
        int pods = 12;
        // Each pod processes ONE early event, so it keeps a stale-LOW local high-water (1..12).
        RedisRunSeqBackend[] backends = new RedisRunSeqBackend[pods];
        for (int i = 0; i < pods; i++) {
            backends[i] = backend();
            backends[i].next(RUN, zeroSeed);
        }
        // The run then advances to H=20 on one pod; FE high-water = 20.
        RedisRunSeqBackend driver = backends[0];
        long feHighWater = 0;
        while ((feHighWater = driver.next(RUN, zeroSeed)) < 20) { /* climb to 20 */ }
        assertEquals(20L, feHighWater);

        // The shared key is LOST; the DB last_event_seq flusher had persisted 20.
        redis.delete(KEY);
        ToLongFunction<String> dbSeed20 = runId -> 20L;

        // All 12 stale-low pods race to emit the next event after the loss.
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        CountDownLatch latch = new CountDownLatch(pods);
        Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger regressions = new AtomicInteger();
        AtomicInteger collisions = new AtomicInteger();
        final long fe = feHighWater;
        for (int i = 0; i < pods; i++) {
            final RedisRunSeqBackend pod = backends[i];
            pool.submit(() -> {
                try {
                    long v = pod.next(RUN, dbSeed20);
                    if (v <= fe) regressions.incrementAndGet();   // would be dropped by FE strict-<
                    if (!seen.add(v)) collisions.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, regressions.get(), "no pod may emit a seq <= the FE high-water after key loss");
        assertEquals(0, collisions.get(), "concurrent reseed must not hand out duplicates");
        assertEquals(pods, seen.size());
    }

    @Test
    @DisplayName("concurrent next on real Redis: distinct, gap-free, strictly increasing (split fan-out across pods)")
    void concurrentDistinct() throws InterruptedException {
        int threads = 16;
        int perThread = 50;
        int total = threads * perThread;
        // Several backends share the one Redis = several pods racing on the same run.
        RedisRunSeqBackend[] pods = new RedisRunSeqBackend[threads];
        for (int i = 0; i < threads; i++) pods[i] = backend();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);
        Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger collisions = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final RedisRunSeqBackend pod = pods[t];
            pool.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    try {
                        if (!seen.add(pod.next(RUN, zeroSeed))) collisions.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, collisions.get(), "shared atomic INCR must never hand out a duplicate across pods");
        assertEquals(total, seen.size());
        // Gap-free 1..total: the shared counter is the single source of truth.
        assertEquals(total + "", redis.opsForValue().get(KEY));
    }
}
