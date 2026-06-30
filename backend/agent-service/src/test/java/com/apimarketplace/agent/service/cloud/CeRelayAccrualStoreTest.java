package com.apimarketplace.agent.service.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeRelayAccrualStore")
class CeRelayAccrualStoreTest {

    private static final String EID = "exec-1";
    private static final String KEY = "ce-relay:accrual:" + EID;
    private static final String INDEX = "ce-relay:accrual:index";

    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private ZSetOperations<String, String> zsetOps;

    private CeRelayAccrualStore store;

    @BeforeEach
    void setUp() {
        store = new CeRelayAccrualStore(redis);
    }

    @Test
    @DisplayName("accrue increments each token field atomically, stamps metadata + index, sets a safety TTL")
    void accrueIncrementsFieldsStampsMetadataAndIndexes() {
        doReturn(hashOps).when(redis).opsForHash();
        doReturn(zsetOps).when(redis).opsForZSet();

        store.accrue(EID, "42", "google", "gemini-3-flash-preview",
                new CeRelayAccrualStore.AccruedUsage(4266, 91, 0, 3999, 0, 107), 1000L);

        verify(hashOps).put(KEY, "userId", "42");
        verify(hashOps).put(KEY, "provider", "google");
        verify(hashOps).put(KEY, "model", "gemini-3-flash-preview");
        verify(hashOps).increment(KEY, "promptTokens", 4266L);
        verify(hashOps).increment(KEY, "completionTokens", 91L);
        verify(hashOps).increment(KEY, "cacheReadTokens", 3999L);
        verify(hashOps).increment(KEY, "reasoningTokens", 107L);
        verify(hashOps).put(KEY, "updatedAt", "1000");
        verify(zsetOps).add(INDEX, EID, 1000.0);
        verify(redis).expire(eq(KEY), eq(168L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("snapshot reconstructs the running totals + metadata from the hash")
    void snapshotReconstructsState() {
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("userId", "42");
        hash.put("provider", "google");
        hash.put("model", "gemini-3-flash-preview");
        hash.put("promptTokens", "8449");
        hash.put("completionTokens", "111");
        hash.put("cacheReadTokens", "3999");
        hash.put("reasoningTokens", "107");
        hash.put("updatedAt", "1000");
        doReturn(hashOps).when(redis).opsForHash();
        when(hashOps.entries(KEY)).thenReturn(hash);

        Optional<CeRelayAccrualStore.AccruedSnapshot> snap = store.snapshot(EID);

        assertThat(snap).isPresent();
        assertThat(snap.get().userId()).isEqualTo("42");
        assertThat(snap.get().provider()).isEqualTo("google");
        assertThat(snap.get().model()).isEqualTo("gemini-3-flash-preview");
        assertThat(snap.get().usage().promptTokens()).isEqualTo(8449);
        assertThat(snap.get().usage().completionTokens()).isEqualTo(111);
        assertThat(snap.get().usage().cacheReadTokens()).isEqualTo(3999);
        assertThat(snap.get().usage().reasoningTokens()).isEqualTo(107);
        assertThat(snap.get().updatedAtEpochMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("snapshot is empty when no accrual exists")
    void snapshotEmptyWhenAbsent() {
        doReturn(hashOps).when(redis).opsForHash();
        when(hashOps.entries(KEY)).thenReturn(Map.of());

        assertThat(store.snapshot(EID)).isEmpty();
    }

    @Test
    @DisplayName("remove drops both the hash and the index entry")
    void removeDropsHashAndIndex() {
        doReturn(zsetOps).when(redis).opsForZSet();

        store.remove(EID);

        verify(redis).delete(KEY);
        verify(zsetOps).remove(INDEX, EID);
    }

    @Test
    @DisplayName("findStale returns execution ids indexed at or before the cutoff")
    void findStaleReturnsAbandonedExecutions() {
        doReturn(zsetOps).when(redis).opsForZSet();
        when(zsetOps.rangeByScore(INDEX, Double.NEGATIVE_INFINITY, 5000.0, 0, 50))
                .thenReturn(new java.util.LinkedHashSet<>(Set.of("a", "b")));

        List<String> stale = store.findStale(5000L, 50);

        assertThat(stale).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("blank executionId is a no-op (no Redis writes)")
    void blankExecutionIdIsNoOp() {
        store.accrue("  ", "42", "google", "m", CeRelayAccrualStore.AccruedUsage.ZERO, 1L);
        assertThat(store.snapshot("")).isEmpty();
        store.remove(null);
        // No opsForHash/opsForZSet interactions expected for blank ids on accrue/remove/snapshot.
    }
}
