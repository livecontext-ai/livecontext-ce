package com.apimarketplace.orchestrator.services.scaling;

import com.apimarketplace.orchestrator.config.OrchestratorInstanceRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase B.2 (archi-refoundation 2026-05-04) - RedisLeaderLock regression tests.
 *
 * <p>Critical invariants:
 * <ul>
 *   <li>SETNX acquire stamps {@code instanceId} as the lock value</li>
 *   <li>{@code amOwner} reads the local cache (no Redis hit on hot path)</li>
 *   <li>Lua RELEASE_LOCK_SCRIPT compares-and-deletes (CAS) to avoid releasing
 *       another instance's lock acquired after TTL expiry</li>
 *   <li>Redis failure during {@code tryAcquire} returns false (fail-closed
 *       for the coalescing path; non-owner falls back to direct publish)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("RedisLeaderLock - Phase B.2 multi-replica ownership")
class RedisLeaderLockTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private OrchestratorInstanceRegistrar registrar;

    private RedisLeaderLock lock;

    @BeforeEach
    void setUp() {
        when(registrar.getInstanceId()).thenReturn("orch-instance-1");
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new RedisLeaderLock(redis, registrar);
    }

    @Test
    @DisplayName("tryAcquire returns true and stamps instanceId when SETNX succeeds (we became owner)")
    void tryAcquireSetsInstanceIdOnSuccess() {
        when(valueOps.setIfAbsent(eq("orch:snapshot:owner:run-A"), eq("orch-instance-1"), any(Duration.class)))
                .thenReturn(true);

        assertTrue(lock.tryAcquire("run-A"),
                "tryAcquire must return true when this instance became the new owner");
        // amOwner must reflect the acquisition without an extra Redis round-trip
        assertTrue(lock.amOwner("run-A"));
        assertEquals(1, lock.ownedCount());

        // SETNX should be the only Redis verb used in the success fast-path -
        // GET is only invoked when SETNX returns false (lock already held).
        verify(valueOps, never()).get(any());
    }

    @Test
    @DisplayName("tryAcquire returns true on idempotent re-acquire when this instance already owned")
    void tryAcquireIdempotentReAcquire() {
        // SETNX fails - lock already held
        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenReturn(false);
        // GET reveals current owner is us - re-add to ownedRuns (idempotent)
        when(valueOps.get(eq("orch:snapshot:owner:run-B"))).thenReturn("orch-instance-1");

        assertTrue(lock.tryAcquire("run-B"),
                "tryAcquire must return true when SETNX fails because we already own the lock");
        assertTrue(lock.amOwner("run-B"));
    }

    @Test
    @DisplayName("tryAcquire returns false when another instance owns the lock")
    void tryAcquireReturnsFalseWhenAnotherOwns() {
        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get(eq("orch:snapshot:owner:run-C"))).thenReturn("orch-instance-2");

        assertFalse(lock.tryAcquire("run-C"));
        assertFalse(lock.amOwner("run-C"));
        assertEquals(0, lock.ownedCount());
    }

    @Test
    @DisplayName("tryAcquire returns false on Redis failure (fail-closed for coalescer path)")
    void tryAcquireFailsClosedOnRedisError() {
        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis unreachable"));

        // Must NOT throw - caller relies on the fail-closed semantics to fall back to direct publish
        assertFalse(lock.tryAcquire("run-D"));
        assertFalse(lock.amOwner("run-D"));
    }

    @Test
    @DisplayName("release executes Lua RELEASE_LOCK_SCRIPT (CAS) to avoid releasing peer's lock")
    void releaseUsesLuaCasScript() {
        // First acquire so we have something to release
        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenReturn(true);
        lock.tryAcquire("run-E");
        assertTrue(lock.amOwner("run-E"));

        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        lock.release("run-E");

        // Lua CAS executed with our instanceId as ARGV[1]
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("orch:snapshot:owner:run-E")),
                eq("orch-instance-1"));
        // Local cache cleared
        assertFalse(lock.amOwner("run-E"));
        assertEquals(0, lock.ownedCount());
    }

    @Test
    @DisplayName("amOwner returns false for runIds we never acquired (fast path, no Redis)")
    void amOwnerFastPathFalseForUnknownRuns() {
        assertFalse(lock.amOwner("never-acquired"));
        // Critical: amOwner must not hit Redis for unknown runs (path chaud).
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("release on un-acquired runId is a defensive no-op (does not throw)")
    void releaseUnacquiredIsNoOp() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);
        assertDoesNotThrow(() -> lock.release("unknown"));
    }

    @Test
    @DisplayName("Null runId is a defensive no-op on tryAcquire/amOwner/release")
    void nullRunIdNoOp() {
        assertFalse(lock.tryAcquire(null));
        assertFalse(lock.amOwner(null));
        assertDoesNotThrow(() -> lock.release(null));
        verifyNoInteractions(valueOps);
    }
}
