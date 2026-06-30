package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TxScopedSnapshotCache")
class TxScopedSnapshotCacheTest {

    private TxScopedSnapshotCache cache;
    private WorkflowRunRepository runRepository;

    @BeforeEach
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        // Default behavior: live DB seq matches whatever we put in (so the
        // 5ms freshness check passes for all existing tests). Individual
        // tests that need to exercise the eviction path stub differently.
        lenient().when(runRepository.findStateSnapshotSeqByRunIdPublic(anyString()))
                .thenReturn(Optional.of(0L));
        cache = new TxScopedSnapshotCache(runRepository, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        // Defensive: reset thread-local sync state between tests, even if a test
        // didn't end its simulated transaction cleanly.
        TransactionSynchronizationManager.clear();
    }

    @Nested
    @DisplayName("Outside an active transaction")
    class OutsideTransaction {

        @Test
        @DisplayName("get returns empty - no cache exists")
        void getReturnsEmptyWhenNoTx() {
            assertThat(cache.get("run-123")).isEmpty();
        }

        @Test
        @DisplayName("put is a silent no-op - no resource is bound")
        void putIsNoOpWhenNoTx() {
            cache.put("run-123", StateSnapshot.empty());

            assertThat(cache.size()).isZero();
            assertThat(cache.get("run-123")).isEmpty();
            assertThat(TransactionSynchronizationManager.hasResource(TxScopedSnapshotCache.class)).isFalse();
        }

        @Test
        @DisplayName("invalidate is a silent no-op")
        void invalidateIsNoOpWhenNoTx() {
            cache.invalidate("run-123");

            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("get(null) returns empty without inspecting the cache")
        void getNullReturnsEmpty() {
            assertThat(cache.get(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Inside an active transaction")
    class InsideTransaction {

        @BeforeEach
        void beginSimulatedTx() {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }

        @AfterEach
        void endSimulatedTx() {
            // Fire afterCompletion so the cache binds release exactly like in prod.
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(0); // STATUS_COMMITTED
            }
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }

        @Test
        @DisplayName("put + get round-trip returns the same instance")
        void putThenGetReturnsSameInstance() {
            StateSnapshot s = StateSnapshot.empty();
            cache.put("run-123", s);

            Optional<StateSnapshot> retrieved = cache.get("run-123");

            assertThat(retrieved).isPresent().get().isSameAs(s);
        }

        @Test
        @DisplayName("size reflects the number of distinct runIds cached")
        void sizeReflectsEntries() {
            cache.put("run-A", StateSnapshot.empty());
            cache.put("run-B", StateSnapshot.empty());
            cache.put("run-A", StateSnapshot.empty()); // overwrite, NOT a new entry

            assertThat(cache.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("put overwrites the previous snapshot for the same runId - last-write wins")
        void putOverwritesPreviousValue() {
            StateSnapshot first = StateSnapshot.empty();
            StateSnapshot second = StateSnapshot.empty();
            cache.put("run-123", first);
            cache.put("run-123", second);

            assertThat(cache.get("run-123")).isPresent().get().isSameAs(second);
        }

        @Test
        @DisplayName("invalidate removes the entry - subsequent get is empty")
        void invalidateRemovesEntry() {
            cache.put("run-123", StateSnapshot.empty());
            assertThat(cache.get("run-123")).isPresent();

            cache.invalidate("run-123");

            assertThat(cache.get("run-123")).isEmpty();
            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("afterCompletion releases the bound resource (the post-tx contract)")
        void afterCompletionReleasesResource() {
            cache.put("run-123", StateSnapshot.empty());
            assertThat(TransactionSynchronizationManager.hasResource(TxScopedSnapshotCache.class)).isTrue();

            // Simulate Spring firing afterCompletion (the @AfterEach fires it too,
            // but we exercise the contract explicitly here for clarity).
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(0);
            }

            assertThat(TransactionSynchronizationManager.hasResource(TxScopedSnapshotCache.class)).isFalse();
        }

        @Test
        @DisplayName("suspend/resume preserves the cache for the outer transaction")
        void suspendResumeKeepsCacheForOuterTx() {
            // Outer tx: set up cache
            StateSnapshot outerSnapshot = StateSnapshot.empty();
            cache.put("run-outer", outerSnapshot);

            // Spring REQUIRES_NEW: suspend the outer tx - our hook should unbind so
            // the inner tx gets a clean cache.
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.suspend();
            }
            assertThat(TransactionSynchronizationManager.hasResource(TxScopedSnapshotCache.class))
                .as("cache must be unbound during suspend so inner tx starts clean")
                .isFalse();

            // Resume back to the outer tx - cache must be re-bound and contents restored.
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.resume();
            }
            assertThat(cache.get("run-outer"))
                .as("outer tx's cached entries must survive a suspend/resume cycle")
                .isPresent().get().isSameAs(outerSnapshot);
        }

        @Test
        @DisplayName("Plan v4 §4 - entry younger than 5ms returns immediately without DB verification")
        void freshEntryShortCircuitsDbCheck() {
            // Brand new entry - age << 5ms. Verifier should NOT be called.
            cache.put("run-fresh", StateSnapshot.empty().withIncrementedSeq());

            Optional<StateSnapshot> got = cache.get("run-fresh");

            assertThat(got).isPresent();
            org.mockito.Mockito.verify(runRepository, org.mockito.Mockito.never())
                    .findStateSnapshotSeqByRunIdPublic(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Plan v4 §4 - entry older than 5ms triggers seq verification; matching seq → return + refresh timestamp")
        void staleEntryVerifiedAgainstDb() throws InterruptedException {
            StateSnapshot snap = StateSnapshot.empty().withIncrementedSeq();  // seq = 1
            // Stub mock so live seq matches.
            org.mockito.Mockito.reset(runRepository);
            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-stale"))
                    .thenReturn(Optional.of(1L));
            cache.put("run-stale", snap);

            // Sleep beyond the 5ms freshness window.
            Thread.sleep(8);

            Optional<StateSnapshot> got = cache.get("run-stale");

            assertThat(got).isPresent().get().isSameAs(snap);
            org.mockito.Mockito.verify(runRepository).findStateSnapshotSeqByRunIdPublic("run-stale");
        }

        @Test
        @DisplayName("Plan v4 §4 - stale entry with seq mismatch (peer commit) → evict + return empty")
        void staleEntryEvictedOnSeqMismatch() throws InterruptedException {
            StateSnapshot snap = StateSnapshot.empty().withIncrementedSeq();  // seq=1
            org.mockito.Mockito.reset(runRepository);
            // Peer committed → live seq advanced beyond our populate-seq.
            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-evicted"))
                    .thenReturn(Optional.of(5L));
            cache.put("run-evicted", snap);

            Thread.sleep(8);
            Optional<StateSnapshot> got = cache.get("run-evicted");

            assertThat(got).isEmpty();
            // Entry should be removed from the cache.
            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("Plan v4 §4 - stale entry with row deleted (Optional.empty from projection) → evict + return empty")
        void staleEntryEvictedOnRowDeleted() throws InterruptedException {
            StateSnapshot snap = StateSnapshot.empty();
            org.mockito.Mockito.reset(runRepository);
            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-deleted"))
                    .thenReturn(Optional.empty());
            cache.put("run-deleted", snap);

            Thread.sleep(8);
            Optional<StateSnapshot> got = cache.get("run-deleted");

            assertThat(got).isEmpty();
        }

        @Test
        @DisplayName("two distinct runIds in the same tx do not collide")
        void distinctRunIdsAreIndependent() {
            StateSnapshot a = StateSnapshot.empty();
            StateSnapshot b = StateSnapshot.empty();
            cache.put("run-A", a);
            cache.put("run-B", b);

            assertThat(cache.get("run-A")).isPresent().get().isSameAs(a);
            assertThat(cache.get("run-B")).isPresent().get().isSameAs(b);
        }

        @Test
        @DisplayName("get(null) returns empty even with active transaction")
        void getNullReturnsEmptyWithTx() {
            cache.put("run-123", StateSnapshot.empty());

            assertThat(cache.get(null)).isEmpty();
        }
    }
}
