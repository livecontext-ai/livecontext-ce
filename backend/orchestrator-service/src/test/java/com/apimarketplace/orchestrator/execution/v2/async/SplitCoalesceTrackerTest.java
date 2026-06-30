package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SplitCoalesceTracker}, the Redis-backed barrier that coalesces
 * per-item async agent completions inside a split scope.
 *
 * <p>Uses Mockito to mock Redis interactions. The Lua script atomicity is tested by
 * controlling the return value of {@code redis.execute(script, ...)}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitCoalesceTracker")
class SplitCoalesceTrackerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private SetOperations<String, String> setOps;

    private ObjectMapper objectMapper;
    private SplitCoalesceTracker tracker;

    private static final String RUN_ID = "run-1";
    private static final String NODE_ID = "agent:a";
    private static final int EPOCH = 0;
    private static final String BARRIER_KEY = "orchestrator:split-barrier:run-1:agent:a:0";
    private static final String BARRIER_TRACKER = "orchestrator:split-barriers:run-1";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        // C3 (2026-05-09): register/arrive(sealed)/cleanupRun all touch the
        // per-run barrier SET tracker. Stub opsForSet globally to avoid NPEs.
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        tracker = new SplitCoalesceTracker(redis, objectMapper);
    }

    private NodeExecutionResult resultFor(String nodeId, int index) {
        Map<String, Object> output = new HashMap<>();
        output.put("idx", index);
        return new NodeExecutionResult(
            nodeId, NodeStatus.COMPLETED, output, Optional.empty(), new HashMap<>(), 10L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // register
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("register stores expected total via HSETNX")
    void registerStoresExpectedTotal() {
        tracker.register(RUN_ID, NODE_ID, EPOCH, 3);

        verify(hashOps).putIfAbsent(BARRIER_KEY, "total", "3");
        verify(hashOps).putIfAbsent(BARRIER_KEY, "arrived", "0");
        verify(redis).expire(eq(BARRIER_KEY), any());
    }

    @Test
    @DisplayName("register rejects non-positive totalItems")
    void registerRejectsZero() {
        assertThatThrownBy(() -> tracker.register(RUN_ID, NODE_ID, EPOCH, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracker.register(RUN_ID, NODE_ID, EPOCH, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isRegistered delegates to HEXISTS on 'total' field")
    void isRegisteredChecksHashField() {
        when(hashOps.hasKey(BARRIER_KEY, "total")).thenReturn(true);
        assertThat(tracker.isRegistered(RUN_ID, NODE_ID, EPOCH)).isTrue();

        when(hashOps.hasKey(BARRIER_KEY, "total")).thenReturn(false);
        assertThat(tracker.isRegistered(RUN_ID, NODE_ID, EPOCH)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // arrive - not sealed
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("arrive returns empty when Lua script returns 0 (not sealed)")
    void arriveNotSealed() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(0L);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 0, resultFor(NODE_ID, 0));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("arrive returns empty when Lua script returns -1 (duplicate)")
    void arriveDuplicate() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(-1L);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 0, resultFor(NODE_ID, 0));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("arrive returns empty when Lua script returns null")
    void arriveNullScript() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(null);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 0, resultFor(NODE_ID, 0));
        assertThat(result).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // arrive - sealed
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("arrive returns sealed batch when Lua script returns 1")
    @SuppressWarnings("unchecked")
    void arriveSealed() throws Exception {
        // Lua script says "sealed"
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(1L);

        // Build the hash entries that loadSealedBatch will read
        Map<Object, Object> hashEntries = buildSealedHashEntries(2);
        when(hashOps.entries(BARRIER_KEY)).thenReturn(hashEntries);
        when(redis.delete(BARRIER_KEY)).thenReturn(true);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 1, resultFor(NODE_ID, 1));

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).itemIndex()).isEqualTo(0);
        assertThat(result.get().get(0).result().nodeId()).isEqualTo(NODE_ID);
        assertThat(result.get().get(1).itemIndex()).isEqualTo(1);
        assertThat(result.get().get(1).result().nodeId()).isEqualTo(NODE_ID);
        verify(redis).delete(BARRIER_KEY);
    }

    @Test
    @DisplayName("arrive returns batch even if redis.delete fails after seal")
    @SuppressWarnings("unchecked")
    void arriveReturnsBatchEvenIfDeleteFails() throws Exception {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(1L);

        Map<Object, Object> hashEntries = buildSealedHashEntries(2);
        when(hashOps.entries(BARRIER_KEY)).thenReturn(hashEntries);
        doThrow(new RuntimeException("Redis connection lost")).when(redis).delete(BARRIER_KEY);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 1, resultFor(NODE_ID, 1));

        // Batch should still be returned despite delete failure
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
    }

    @Test
    @DisplayName("arrive returns sparse batch with original indices when upstream filtering short-circuits items")
    @SuppressWarnings("unchecked")
    void arriveSealedWithSparseIndices() throws Exception {
        // Regression test: is_new decision skips 11/15 split items, only items {0, 1, 2, 7}
        // reach classify. Barrier registered with totalAsync=4. Items arrive at indices
        // 0, 1, 2, 7. Before the fix, loadSealedBatch iterated 0..total-1=3, picking up
        // items 0/1/2 and dropping item 7, emitting "Missing item at index 3" warning.
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(1L);

        Map<Object, Object> hashEntries = new LinkedHashMap<>();
        hashEntries.put("total", "4");
        hashEntries.put("arrived", "4");
        for (int idx : new int[]{0, 1, 2, 7}) {
            NodeExecutionResult result = resultFor(NODE_ID, idx);
            Map<String, Object> map = new HashMap<>();
            map.put("nodeId", result.nodeId());
            map.put("status", result.status().name());
            map.put("output", result.output());
            map.put("errorMessage", result.errorMessage().orElse(null));
            map.put("metadata", result.metadata());
            map.put("durationMs", result.durationMs());
            hashEntries.put("item:" + idx, objectMapper.writeValueAsString(map));
        }
        when(hashOps.entries(BARRIER_KEY)).thenReturn(hashEntries);
        when(redis.delete(BARRIER_KEY)).thenReturn(true);

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 7, resultFor(NODE_ID, 7));

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(4); // all four items, not three
        // Sorted ascending by itemIndex
        assertThat(result.get().get(0).itemIndex()).isEqualTo(0);
        assertThat(result.get().get(1).itemIndex()).isEqualTo(1);
        assertThat(result.get().get(2).itemIndex()).isEqualTo(2);
        assertThat(result.get().get(3).itemIndex()).isEqualTo(7); // preserved, not remapped to 3
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("register SADDs nodeId:epoch into the per-run barrier tracker SET (C3 - anti redis.keys blocking, 2026-05-09)")
    void registerSaddsToTracker() {
        tracker.register(RUN_ID, NODE_ID, EPOCH, 3);

        verify(setOps).add(BARRIER_TRACKER, "agent:a:0");
        verify(redis).expire(eq(BARRIER_TRACKER), any());
    }

    @Test
    @DisplayName("arrive (sealed) SREMs the nodeId:epoch from the tracker SET so cleanupRun won't see a stale entry")
    @SuppressWarnings("unchecked")
    void arriveSealedSremsTrackerEntry() throws Exception {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenReturn(1L);
        Map<Object, Object> hashEntries = buildSealedHashEntries(2);
        when(hashOps.entries(BARRIER_KEY)).thenReturn(hashEntries);

        tracker.arrive(RUN_ID, NODE_ID, EPOCH, 1, resultFor(NODE_ID, 1));

        verify(setOps).remove(BARRIER_TRACKER, new Object[]{"agent:a:0"});
    }

    @Test
    @DisplayName("cleanupRun reads from tracker SET via SMEMBERS - does NOT invoke redis.keys (regression: blocking KEYS bottleneck)")
    void cleanupRunUsesTrackerSetFastPath() {
        when(setOps.members(BARRIER_TRACKER))
            .thenReturn(Set.of("agent:a:0", "agent:b:1"));

        tracker.cleanupRun(RUN_ID);

        // Bulk delete the keys derived from the tracker entries (List, order unspecified
        // because the SET is unordered - match by content via argThat).
        verify(redis).delete(org.mockito.ArgumentMatchers.<List<String>>argThat(list ->
            list != null && list.size() == 2
                && list.contains("orchestrator:split-barrier:run-1:agent:a:0")
                && list.contains("orchestrator:split-barrier:run-1:agent:b:1")));
        // Always evict the tracker SET itself.
        verify(redis).delete(BARRIER_TRACKER);
        // Critical: no blocking KEYS scan on the fast path.
        verify(redis, never()).keys(anyString());
    }

    @Test
    @DisplayName("cleanupRun drift fallback: empty tracker SET → KEYS scan → bulk delete")
    void cleanupRunDriftFallbackUsesKeysScan() {
        when(setOps.members(BARRIER_TRACKER)).thenReturn(Set.of());
        Set<String> keys = Set.of(
            "orchestrator:split-barrier:run-1:agent:a:0",
            "orchestrator:split-barrier:run-1:agent:b:1");
        when(redis.keys("orchestrator:split-barrier:run-1:*")).thenReturn(keys);

        tracker.cleanupRun(RUN_ID);

        // Drift path passes the keys as a List<String> for bulk DEL.
        verify(redis).delete(org.mockito.ArgumentMatchers.<List<String>>argThat(list ->
            list != null && list.size() == 2 && list.containsAll(keys)));
        verify(redis).delete(BARRIER_TRACKER);
    }

    @Test
    @DisplayName("cleanupRun is no-op (bulk-delete-wise) when both tracker and KEYS are empty - still evicts the tracker SET")
    void cleanupRunNoOp() {
        when(setOps.members(BARRIER_TRACKER)).thenReturn(Set.of());
        when(redis.keys("orchestrator:split-barrier:run-1:*")).thenReturn(Set.of());

        tracker.cleanupRun(RUN_ID);

        verify(redis, never()).delete(any(java.util.Collection.class));
        verify(redis).delete(BARRIER_TRACKER);
    }

    @Test
    @DisplayName("getCacheName returns correct name")
    void getCacheNameReturnsCorrectName() {
        assertThat(tracker.getCacheName()).isEqualTo("SplitCoalesceTracker");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Redis failure resilience
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("register swallows Redis exceptions (non-fatal)")
    void registerSwallowsRedisException() {
        when(hashOps.putIfAbsent(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis down"));

        // Should not throw
        tracker.register(RUN_ID, NODE_ID, EPOCH, 3);
    }

    @Test
    @DisplayName("arrive returns empty on Redis exception (non-fatal)")
    void arriveSwallowsRedisException() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis down"));

        Optional<List<IndexedNodeResult>> result =
            tracker.arrive(RUN_ID, NODE_ID, EPOCH, 0, resultFor(NODE_ID, 0));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("isRegistered returns false on Redis exception")
    void isRegisteredReturnsFalseOnException() {
        when(hashOps.hasKey(anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis down"));

        assertThat(tracker.isRegistered(RUN_ID, NODE_ID, EPOCH)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<Object, Object> buildSealedHashEntries(int total) throws Exception {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("total", String.valueOf(total));
        entries.put("arrived", String.valueOf(total));
        for (int i = 0; i < total; i++) {
            NodeExecutionResult result = resultFor(NODE_ID, i);
            Map<String, Object> map = new HashMap<>();
            map.put("nodeId", result.nodeId());
            map.put("status", result.status().name());
            map.put("output", result.output());
            map.put("errorMessage", result.errorMessage().orElse(null));
            map.put("metadata", result.metadata());
            map.put("durationMs", result.durationMs());
            entries.put("item:" + i, objectMapper.writeValueAsString(map));
        }
        return entries;
    }
}
