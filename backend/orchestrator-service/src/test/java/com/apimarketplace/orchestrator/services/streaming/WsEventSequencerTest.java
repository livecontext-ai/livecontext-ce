package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase A2 (archi-refoundation 2026-05-04) - WsEventSequencer regression tests.
 *
 * Critical invariants:
 * <ul>
 *   <li>nextSeq() is strictly monotonic per runId (frontend lastKnownSeq strict-{@code <})</li>
 *   <li>Cross-restart: first call seeds from DB last_event_seq</li>
 *   <li>cleanup via RunScopedCache integration removes entries</li>
 *   <li>Thread-safe under concurrent calls (split with N parallel items)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WsEventSequencer")
class WsEventSequencerTest {

    @Mock
    private WorkflowRunRepository runRepository;

    private WsEventSequencer sequencer;

    @BeforeEach
    void setUp() {
        sequencer = new WsEventSequencer(runRepository);
    }

    @Test
    @DisplayName("nextSeq is strictly monotonic per runId across calls")
    void nextSeqMonotonic() {
        when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

        long first = sequencer.nextSeq("run-1");
        long second = sequencer.nextSeq("run-1");
        long third = sequencer.nextSeq("run-1");

        assertEquals(1L, first);
        assertEquals(2L, second);
        assertEquals(3L, third);
        assertTrue(second > first);
        assertTrue(third > second);
    }

    @Test
    @DisplayName("Different runIds get independent counters (no cross-contamination)")
    void independentCountersPerRun() {
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.empty());

        long a1 = sequencer.nextSeq("run-A");
        long b1 = sequencer.nextSeq("run-B");
        long a2 = sequencer.nextSeq("run-A");

        assertEquals(1L, a1);
        assertEquals(1L, b1, "Each runId must have its own counter starting at last_event_seq + 1");
        assertEquals(2L, a2);
    }

    @Test
    @DisplayName("Cross-restart: first call seeds the counter from DB last_event_seq (audit B v6 C1.2)")
    void seedsFromDbOnFirstCall() {
        WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
        when(entity.getLastEventSeq()).thenReturn(500L);
        when(runRepository.findByRunIdPublic("run-restarted")).thenReturn(Optional.of(entity));

        long first = sequencer.nextSeq("run-restarted");
        long second = sequencer.nextSeq("run-restarted");

        assertEquals(501L, first, "First call after restart must seed from DB+1, not start at 1");
        assertEquals(502L, second);
        // DB queried once (subsequent calls use the in-memory atomic)
        verify(runRepository, times(1)).findByRunIdPublic("run-restarted");
    }

    @Test
    @DisplayName("DB seed failure falls back to 0 (graceful degradation, not crash)")
    void seedFailureFallsBackToZero() {
        when(runRepository.findByRunIdPublic("run-broken")).thenThrow(new RuntimeException("DB down"));

        long first = sequencer.nextSeq("run-broken");

        assertEquals(1L, first, "DB unreachable at seed time must not crash markDirty");
    }

    @Test
    @DisplayName("Concurrent nextSeq calls produce strictly distinct values (split parallel safety)")
    void concurrentCallsProduceDistinctSeqs() throws InterruptedException {
        when(runRepository.findByRunIdPublic("run-concurrent")).thenReturn(Optional.empty());

        int threads = 16;
        int callsPerThread = 100;
        int total = threads * callsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger collisions = new AtomicInteger(0);

        java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    long s = sequencer.nextSeq("run-concurrent");
                    if (!seen.add(s)) {
                        collisions.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, collisions.get(),
                "AtomicLong-backed counter must produce no duplicates under concurrent load. "
              + "Without this guarantee, frontend lastKnownSeq strict-< guard would let stale events leak.");
        assertEquals(total, seen.size());
    }

    @Test
    @DisplayName("cleanupRun synchronously flushes the counter to DB, then removes from memory")
    void cleanupRunFlushesBeforePurge() {
        when(runRepository.findByRunIdPublic("run-x")).thenReturn(Optional.empty());

        sequencer.nextSeq("run-x");  // creates counter, dirty=1
        sequencer.nextSeq("run-x");  // bumps counter, dirty=2

        assertEquals(2L, sequencer.currentSeq("run-x"));

        sequencer.cleanupRun("run-x");

        // Synchronous flush BEFORE purge - DB receives the high-water mark.
        verify(runRepository, times(1)).upsertLastEventSeqSingle("run-x", 2L);
        // In-memory counter is purged.
        assertEquals(0, sequencer.getCacheSize());
    }

    @Test
    @DisplayName("Reusable trigger: fire #1 cleanup → fire #2 reseeds from DB high-water (regression)")
    void reusableTriggerCleanupReseedsFromDbHighWater() {
        // Simulate the prod sequence:
        //   1. Fire #1 emits seq=1..67 on runId=run-A
        //   2. Reusable trigger epoch close → cleanupRun(run-A) (RunContextRegistry
        //      .closeEpochForDagByTriggerId path)
        //   3. Fire #2 starts; nextSeq lazy-seeds from DB
        //
        // Pre-fix: cleanupRun discarded the dirty seq before the @Scheduled flusher
        // tick, DB stayed at 0, fire #2 emitted seq=1, 2, ... - frontend with
        // lastKnownSeq=67 strict-< dropped them all and the UI froze.
        //
        // Post-fix: cleanupRun flushes synchronously, DB=67, fire #2 emits seq=68,
        // 69, ... - frontend accepts.
        when(runRepository.findByRunIdPublic("run-A")).thenReturn(Optional.empty());
        when(runRepository.upsertLastEventSeqSingle(anyString(), anyLong())).thenAnswer(invocation -> {
            // Mimic the upsert: subsequent findByRunIdPublic must return the new seq.
            String runId = invocation.getArgument(0);
            long newSeq = invocation.getArgument(1);
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getLastEventSeq()).thenReturn(newSeq);
            when(runRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(entity));
            return 1;
        });

        // Fire #1 - bump to 67.
        for (int i = 0; i < 67; i++) {
            sequencer.nextSeq("run-A");
        }
        assertEquals(67L, sequencer.currentSeq("run-A"));

        // Epoch close path - equivalent to RunContextRegistry calling cacheRegistry.cleanupRun.
        sequencer.cleanupRun("run-A");

        // Fire #2 - first nextSeq must NOT regress to 1.
        long fire2First = sequencer.nextSeq("run-A");
        assertEquals(68L, fire2First, "Fire #2 must continue from DB-flushed high-water 67, not reset to 1");
    }

    @Test
    @DisplayName("Implements RunScopedCache contract for auto-registration")
    void runScopedCacheContract() {
        assertEquals("WsEventSequencer", sequencer.getCacheName());
        assertEquals(RunScopedCache.CacheDomain.STREAMING, sequencer.getDomain());

        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.empty());
        sequencer.nextSeq("run-1");
        sequencer.nextSeq("run-2");

        assertEquals(2, sequencer.getCacheSize());
    }

    @Test
    @DisplayName("flushPersistedSeq is no-op when no runs are dirty")
    void flushNoOpWhenClean() {
        sequencer.flushPersistedSeq();
        verify(runRepository, never()).upsertLastEventSeqSingle(anyString(), anyLong());
    }

    @Test
    @DisplayName("flushPersistedSeq writes only dirty runs to DB (batched scan)")
    void flushWritesDirtyRuns() {
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.empty());
        when(runRepository.upsertLastEventSeqSingle(anyString(), anyLong())).thenReturn(1);

        sequencer.nextSeq("run-A");  // dirty=1
        sequencer.nextSeq("run-A");  // dirty=2
        sequencer.nextSeq("run-B");  // dirty=1

        sequencer.flushPersistedSeq();

        verify(runRepository, times(1)).upsertLastEventSeqSingle("run-A", 2L);
        verify(runRepository, times(1)).upsertLastEventSeqSingle("run-B", 1L);

        // Subsequent flush with no new bumps must not write
        clearInvocations(runRepository);
        sequencer.flushPersistedSeq();
        verify(runRepository, never()).upsertLastEventSeqSingle(anyString(), anyLong());
    }

    @Test
    @DisplayName("currentSeq is read-only and returns 0 for unknown runs")
    void currentSeqReadOnly() {
        assertEquals(0L, sequencer.currentSeq("unknown-run"));
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for null runId (defensive guard)")
    void rejectsNullRunId() {
        assertThrows(IllegalArgumentException.class, () -> sequencer.nextSeq(null));
    }

    @Test
    @DisplayName("Delegates seq storage to the injected backend (multi-pod Redis wiring) and flushes the AUTHORITATIVE shared value on cleanup")
    void delegatesToInjectedBackend() {
        // When wired with a RedisRunSeqBackend in prod, the sequencer must source
        // seq from the backend (shared counter) and, on cleanup, flush the
        // AUTHORITATIVE shared high-water - NOT just this pod's local peek - so a
        // later refire that re-seeds from the DB cannot regress below the FE seq.
        RunSeqBackend backend = mock(RunSeqBackend.class);
        when(backend.next(eq("run-z"), any())).thenReturn(99L);
        when(backend.peek("run-z")).thenReturn(OptionalLong.of(99L));   // this pod's local view
        when(backend.current(eq("run-z"), any())).thenReturn(150L);     // shared value another pod drove higher

        WsEventSequencer seq = new WsEventSequencer(runRepository, backend);

        assertEquals(99L, seq.nextSeq("run-z"));
        verify(backend).next(eq("run-z"), any());

        seq.cleanupRun("run-z");
        // Fix A: flushes max(peek=99, current=150) = 150, the shared high-water - then purges.
        verify(runRepository).upsertLastEventSeqSingle("run-z", 150L);
        verify(runRepository, never()).upsertLastEventSeqSingle("run-z", 99L);
        verify(backend).remove("run-z");
    }

    @Test
    @DisplayName("cleanup does NOT flush when this pod never touched the run (peek empty) - no spurious DB write")
    void cleanupNoFlushWhenUntouched() {
        RunSeqBackend backend = mock(RunSeqBackend.class);
        when(backend.peek("run-untouched")).thenReturn(OptionalLong.empty());

        WsEventSequencer seq = new WsEventSequencer(runRepository, backend);
        seq.cleanupRun("run-untouched");

        verify(runRepository, never()).upsertLastEventSeqSingle(anyString(), anyLong());
        verify(backend).remove("run-untouched");
    }
}
