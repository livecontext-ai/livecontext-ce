package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RedisInFlightStore#localSize()} is what the shutdown drain waits on, so it must count
 * exactly the deliveries THIS instance has staged and not yet cleared, independent of what the
 * shared Redis keyspace holds and of whether Redis accepted the stage.
 */
class RedisInFlightStoreLocalSizeTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisInFlightStore store = new RedisInFlightStore(redis, new ObjectMapper().findAndRegisterModules());

    private static PendingAgent pending(String correlationId) {
        return new PendingAgent(
            correlationId, "run_1", "agent:classify", "Classify", "trigger:t",
            1, 0, null, "classify", "tenant-1",
            null, null, null, null, null, "model", null, null, Instant.now(), "org-1");
    }

    private static AgentResultMessage result(String correlationId) {
        return new AgentResultMessage(correlationId, "run_1", "agent:classify", null, true, null, "classify", Instant.now());
    }

    @Test
    @DisplayName("stage counts the delivery locally and clear releases it")
    @SuppressWarnings("unchecked")
    void stageThenClear() {
        when(redis.execute(any(SessionCallback.class))).thenReturn(List.of());

        store.stage(pending("cid-1"), result("cid-1"));
        store.stage(pending("cid-2"), result("cid-2"));
        assertThat(store.localSize()).isEqualTo(2);

        store.clear("cid-1");
        assertThat(store.localSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("A stage Redis refuses still counts: the delivery is running on this instance regardless")
    @SuppressWarnings("unchecked")
    void stageCountsEvenWhenRedisFails() {
        when(redis.execute(any(SessionCallback.class))).thenThrow(new IllegalStateException("redis down"));

        store.stage(pending("cid-1"), result("cid-1"));

        assertThat(store.localSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Re-staging the same correlation id counts once; clearing it (even when the Redis delete fails) releases it")
    @SuppressWarnings("unchecked")
    void restageAndFailingClear() {
        when(redis.execute(any(SessionCallback.class))).thenReturn(List.of());
        doThrow(new IllegalStateException("redis down")).when(redis).delete("agent:in_flight:cid-1");

        store.stage(pending("cid-1"), result("cid-1"));
        store.stage(pending("cid-1"), result("cid-1"));
        assertThat(store.localSize()).isEqualTo(1);

        store.clear("cid-1");
        assertThat(store.localSize()).isZero();
    }

    @Test
    @DisplayName("Clearing an entry another instance staged (startup replay) does not go negative")
    void clearOfForeignEntryIsNoOp() {
        store.clear("cid-from-a-dead-replica");

        assertThat(store.localSize()).isZero();
    }

    @Test
    @DisplayName("Null inputs are ignored: nothing counted, no Redis call")
    void nullInputsIgnored() {
        store.stage(null, result("cid-1"));
        store.stage(pending("cid-1"), null);
        store.clear(null);
        store.clear("");

        assertThat(store.localSize()).isZero();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("localSize never touches Redis (it is read on every drain observation)")
    void localSizeIsRedisFree() {
        assertThat(store.localSize()).isZero();

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("deleteStagedEntry removes the Redis entry but keeps the delivery counted locally until clear")
    @SuppressWarnings("unchecked")
    void deleteStagedEntryKeepsLocalCount() {
        when(redis.execute(any(SessionCallback.class))).thenReturn(List.of());
        store.stage(pending("cid-1"), result("cid-1"));

        store.deleteStagedEntry("cid-1");
        assertThat(store.localSize()).isEqualTo(1);
        org.mockito.Mockito.verify(redis).delete("agent:in_flight:cid-1");

        store.clear("cid-1");
        assertThat(store.localSize()).isZero();
    }
}
