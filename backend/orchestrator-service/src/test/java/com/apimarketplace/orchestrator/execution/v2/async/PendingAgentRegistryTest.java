package com.apimarketplace.orchestrator.execution.v2.async;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingAgentRegistry}.
 *
 * <p>Two regimes are tested:
 * <ul>
 *   <li><b>memory-only</b>: redisStore is null (queue disabled). Behaviour matches the
 *       pre-recovery implementation - register/consume only touch the in-memory map.</li>
 *   <li><b>with side-store</b>: redisStore is wired (queue enabled). Every register
 *       mirrors to Redis, every consume removes from Redis.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingAgentRegistry")
class PendingAgentRegistryTest {

    private PendingAgentRegistry registry;

    @Mock
    private RedisPendingAgentStore redisStore;

    @BeforeEach
    void setUp() {
        registry = new PendingAgentRegistry();
    }

    private PendingAgent agent(String correlationId) {
        return new PendingAgent(
            correlationId, "run-1", "agent:test", "test", "trigger:default",
            0, 0, "0", "agent", "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
    }

    @Nested
    @DisplayName("memory-only (queue disabled)")
    class MemoryOnly {

        @Test
        @DisplayName("register then consume returns the entry")
        void registerThenConsume() {
            PendingAgent a = agent("c-1");
            registry.register(a);
            assertThat(registry.size()).isEqualTo(1);

            Optional<PendingAgent> consumed = registry.consume("c-1");
            assertThat(consumed).contains(a);
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("consume of unknown id returns empty")
        void consumeUnknown() {
            assertThat(registry.consume("unknown")).isEmpty();
        }

        @Test
        @DisplayName("register rejects null agent / null correlationId")
        void registerRejectsNull() {
            assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.register(
                new PendingAgent(null, "run", "n", "l", "t", 0, 0, "0", "agent",
                    "tenant", null, null, null, null, null, null, null, null, Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("hasAnyPendingForRun returns true when any entry matches the run, regardless of dagTriggerId/epoch")
        void hasAnyPendingForRunMatchesAnyDagOrEpoch() {
            // Two entries on different (dag, epoch) tuples but the same run.
            registry.register(new PendingAgent("c-1", "run-X", "agent:a", "a",
                "trigger:default", 0, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now()));
            registry.register(new PendingAgent("c-2", "run-X", "agent:b", "b",
                "trigger:other", 7, 0, "0", "classify", "tenant",
                null, null, null, null, null, null, null, null, Instant.now()));
            // Decoy: different run.
            registry.register(new PendingAgent("c-3", "run-Y", "agent:c", "c",
                "trigger:default", 0, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now()));

            assertThat(registry.hasAnyPendingForRun("run-X")).isTrue();
            assertThat(registry.hasAnyPendingForRun("run-Y")).isTrue();
            assertThat(registry.hasAnyPendingForRun("run-Z")).isFalse();
        }

        @Test
        @DisplayName("hasAnyPendingForRun returns false on null runId or empty registry")
        void hasAnyPendingForRunHandlesNullAndEmpty() {
            assertThat(registry.hasAnyPendingForRun(null)).isFalse();
            assertThat(registry.hasAnyPendingForRun("any")).isFalse();
        }

        @Test
        @DisplayName("findStale returns only entries past the cutoff")
        void findStaleFiltersByTimestamp() {
            PendingAgent fresh = new PendingAgent("c-fresh", "run", "n", "l", "t", 0, 0, "0",
                "agent", "tenant", null, null, null, null, null, null, null, null, Instant.now());
            PendingAgent old = new PendingAgent("c-old", "run", "n", "l", "t", 0, 0, "0",
                "agent", "tenant", null, null, null, null, null, null, null, null, Instant.now().minus(Duration.ofMinutes(45)));
            registry.register(fresh);
            registry.register(old);

            assertThat(registry.findStale(Duration.ofMinutes(30)))
                .extracting(PendingAgent::correlationId)
                .containsExactly("c-old");
        }
    }

    @Nested
    @DisplayName("with Redis side-store (queue enabled)")
    class WithSideStore {

        @BeforeEach
        void wire() {
            registry.setRedisStore(redisStore);
        }

        @Test
        @DisplayName("register mirrors the entry into Redis")
        void registerMirrorsToRedis() {
            PendingAgent a = agent("c-mirror");
            registry.register(a);
            verify(redisStore).store(a);
        }

        @Test
        @DisplayName("hasAnyPendingForRun falls back to Redis when local map has no match (cross-replica gap)")
        void hasAnyPendingForRunConsultsRedisWhenLocalEmpty() {
            // The pending entry was registered on a DIFFERENT replica; this replica's
            // in-memory map is empty for run-A. The watchdog must still see it via Redis.
            when(redisStore.hasAnyForRun("run-A")).thenReturn(true);

            assertThat(registry.hasAnyPendingForRun("run-A")).isTrue();
            verify(redisStore).hasAnyForRun("run-A");
        }

        @Test
        @DisplayName("hasAnyPendingForRun does not query Redis when local map already matches")
        void hasAnyPendingForRunSkipsRedisWhenLocalHits() {
            registry.register(agent("c-local"));

            assertThat(registry.hasAnyPendingForRun("run-1")).isTrue();
            verify(redisStore, org.mockito.Mockito.never()).hasAnyForRun(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("removeByRunId cleans both the local map AND the Redis side-store (no orphan index members)")
        void removeByRunIdCleansBothLocalAndRedis() {
            // Two entries on the same run, one decoy on a different run.
            PendingAgent a1 = new PendingAgent("c-1", "run-X", "agent:a", "a",
                "trigger:default", 0, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now());
            PendingAgent a2 = new PendingAgent("c-2", "run-X", "agent:b", "b",
                "trigger:default", 0, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now());
            PendingAgent decoy = new PendingAgent("c-3", "run-Y", "agent:c", "c",
                "trigger:default", 0, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now());
            registry.register(a1);
            registry.register(a2);
            registry.register(decoy);

            int removed = registry.removeByRunId("run-X");

            // Local: only run-X entries removed; decoy remains.
            assertThat(removed).isEqualTo(2);
            assertThat(registry.size()).isEqualTo(1);

            // Redis: claim invoked for each run-X correlationId (atomic GETDEL also removes
            // the side-store value AND drops the SADD member from agent:pending:run:run-X).
            // Without this guarantee, hasAnyForRun would return true forever (until 2h TTL),
            // and other replicas' watchdogs would skip a run that was already cancelled.
            verify(redisStore).claim("c-1");
            verify(redisStore).claim("c-2");
            verify(redisStore, org.mockito.Mockito.never()).claim("c-3");
        }

        @Test
        @DisplayName("removeByRunId on unknown run is a no-op - no Redis traffic, no exception")
        void removeByRunIdOnUnknownRunIsNoOp() {
            registry.register(agent("c-x"));   // run-1 entry, irrelevant

            int removed = registry.removeByRunId("run-does-not-exist");

            assertThat(removed).isZero();
            verify(redisStore, org.mockito.Mockito.never()).claim(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("consume returns the Redis-claimed entry when GETDEL wins")
        void consumeWinsClaimAndReturnsEntry() {
            PendingAgent a = agent("c-rm");
            when(redisStore.claim("c-rm")).thenReturn(Optional.of(a));
            registry.register(a);

            Optional<PendingAgent> result = registry.consume("c-rm");

            assertThat(result).contains(a);
            verify(redisStore).claim("c-rm");
        }

        @Test
        @DisplayName("consume returns the Redis-claimed entry even when local map is empty (no-strand fix)")
        void consumeReturnsRedisPayloadWhenLocalEmpty() {
            // This is the critical stranding-bug regression test: local map has nothing
            // (e.g., crash-recovered replica that hasn't yet reached registerFromRecovery),
            // Redis GETDEL wins the race. We MUST return the Redis payload - otherwise the
            // Redis key is already deleted and the run is stranded.
            PendingAgent a = agent("c-strand");
            when(redisStore.claim("c-strand")).thenReturn(Optional.of(a));
            // NOTE: no register() / registerFromRecovery() call - local map is empty

            Optional<PendingAgent> result = registry.consume("c-strand");

            assertThat(result).contains(a);
            verify(redisStore).claim("c-strand");
        }

        @Test
        @DisplayName("consume returns empty when Redis claim returns empty (another replica already won)")
        void consumeLostClaimReturnsEmpty() {
            when(redisStore.claim(anyString())).thenReturn(Optional.empty());
            PendingAgent a = agent("c-race");
            registry.registerFromRecovery(a);

            Optional<PendingAgent> result = registry.consume("c-race");

            assertThat(result).isEmpty();
            verify(redisStore).claim("c-race");
        }

        @Test
        @DisplayName("consume of unknown id still calls Redis claim (the GETDEL is the cross-replica barrier)")
        void consumeUnknownStillCallsRedis() {
            when(redisStore.claim(anyString())).thenReturn(Optional.empty());
            Optional<PendingAgent> result = registry.consume("unknown");
            assertThat(result).isEmpty();
            verify(redisStore).claim("unknown");
        }

        @Test
        @DisplayName("consume returns empty AND drops local copy when Redis claim throws (fail-closed, audit P0 #2 - 2026-05-06)")
        void consumeOnRedisFailureFailsClosedAndDropsLocalCopy() {
            // Pre-fix this test asserted fail-open (return the local copy on Redis hiccup).
            // That was a cross-replica double-delivery vector: 2 replicas with the same
            // correlationId in their local maps both pop and deliver.
            // Post-fix: return empty (AgentRecoveryService picks up the missed delivery
            // on its next scan via the result-key TTL). Also drop the local copy so a
            // transient Redis hiccup doesn't leave a ghost entry behind.
            when(redisStore.claim(anyString())).thenThrow(new RuntimeException("redis down"));
            PendingAgent a = agent("c-fallback");
            registry.register(a);
            // Reset interactions so verify(redisStore).store(a) from register() doesn't pollute.

            Optional<PendingAgent> result = registry.consume("c-fallback");

            assertThat(result).isEmpty();
            // Local copy MUST be dropped so subsequent consume() doesn't return a stale entry.
            assertThat(registry.peek("c-fallback")).isEmpty();
        }

        @Test
        @DisplayName("registerFromRecovery skips the Redis mirror to avoid refreshing TTL")
        void recoveryDoesNotMirrorBack() {
            PendingAgent a = agent("c-recover");
            registry.registerFromRecovery(a);
            assertThat(registry.peek("c-recover")).isPresent();
            verifyNoInteractions(redisStore);
        }

        @Test
        @DisplayName("registerFromRecovery is putIfAbsent - does not overwrite a fresher entry")
        void recoveryDoesNotOverwriteFresh() {
            PendingAgent fresh = agent("c-x");
            registry.register(fresh);
            // Recovery later finds the same id with a stale entry - must NOT clobber
            PendingAgent stale = new PendingAgent("c-x", "run-x-old", "agent:other", "other",
                "trigger:default", 0, 0, "0", "agent", "tenant-1", null, null, null, null, null, null,
                null, null, Instant.now().minus(Duration.ofMinutes(20)));
            registry.registerFromRecovery(stale);

            Optional<PendingAgent> peeked = registry.peek("c-x");
            assertThat(peeked).isPresent();
            assertThat(peeked.get().runId()).isEqualTo("run-1"); // fresh, not stale
        }

        @Test
        @DisplayName("registerFromRecovery ignores null agent")
        void recoveryIgnoresNull() {
            registry.registerFromRecovery(null);
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("hasPendingFor falls back to Redis when local map has no match for (run, trigger, epoch) (regression: Gmail Auto-Labeler 2026-05-06)")
        void hasPendingForConsultsRedisWhenLocalEmpty() {
            // Pre-fix: this replica's local map didn't see the entry registered by another
            // replica → resetForNextCycle's pending-agent guard returned false → epoch closed
            // while async classifies were still in flight → late results lost their successors.
            when(redisStore.hasPendingFor("run-cross", "trigger:cron", 51)).thenReturn(true);

            assertThat(registry.hasPendingFor("run-cross", "trigger:cron", 51)).isTrue();
            verify(redisStore).hasPendingFor("run-cross", "trigger:cron", 51);
        }

        @Test
        @DisplayName("hasPendingFor returns false when neither local map nor Redis has a match")
        void hasPendingForReturnsFalseWhenNeitherSourceHasIt() {
            when(redisStore.hasPendingFor("run-empty", "trigger:cron", 1)).thenReturn(false);

            assertThat(registry.hasPendingFor("run-empty", "trigger:cron", 1)).isFalse();
            verify(redisStore).hasPendingFor("run-empty", "trigger:cron", 1);
        }

        @Test
        @DisplayName("hasPendingFor short-circuits on local hit and does NOT consult Redis")
        void hasPendingForSkipsRedisOnLocalHit() {
            // Local map has a matching entry on the same (run, trigger, epoch).
            registry.register(new PendingAgent("c-local", "run-local", "agent:a", "a",
                "trigger:webhook", 3, 0, "0", "agent", "tenant",
                null, null, null, null, null, null, null, null, Instant.now()));

            assertThat(registry.hasPendingFor("run-local", "trigger:webhook", 3)).isTrue();
            verify(redisStore, org.mockito.Mockito.never())
                .hasPendingFor(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        }
    }
}
