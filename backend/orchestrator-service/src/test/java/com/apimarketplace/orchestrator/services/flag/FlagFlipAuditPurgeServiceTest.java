package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlagFlipAuditPurgeService")
class FlagFlipAuditPurgeServiceTest {

    private static final int BATCH_SIZE = 10_000;

    @Mock private FlagFlipAuditRepository repository;

    private FlagFlipAuditPurgeService service(long retentionDays) {
        return new FlagFlipAuditPurgeService(repository, retentionDays);
    }

    @Test
    @DisplayName("purgeNow deletes nothing when table is empty - single repo call returning 0")
    void emptyTableSingleCall() {
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE))).thenReturn(0);

        int total = service(90).purgeNow();

        assertEquals(0, total);
        verify(repository, times(1)).deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("purgeNow loops batches until under-threshold - drains backlog in BATCH_SIZE chunks")
    void backlogDrainsInBatches() {
        // 25_000 stale rows: 10k, 10k, 5k → 3 calls returning 10000, 10000, 5000.
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE)))
                .thenReturn(10_000)
                .thenReturn(10_000)
                .thenReturn(5_000);

        int total = service(90).purgeNow();

        assertEquals(25_000, total);
        verify(repository, times(3)).deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("purgeNow stops looping when batch returns < BATCH_SIZE - no extra repo call after partial drain")
    void stopsAtPartialBatch() {
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE)))
                .thenReturn(42);  // < BATCH_SIZE → loop exits immediately

        int total = service(90).purgeNow();

        assertEquals(42, total);
        verify(repository, times(1)).deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("purgeNow returns -1 on repository exception - graceful failure, no rethrow")
    void returnsMinusOneOnException() {
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE)))
                .thenThrow(new RuntimeException("DB connection refused"));

        int total = service(90).purgeNow();

        assertEquals(-1, total);
    }

    @Test
    @DisplayName("Cutoff Instant is now-minus-retentionDays - verifiable via captor")
    void cutoffComputedCorrectly() {
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE))).thenReturn(0);
        Instant before = Instant.now();

        service(90).purgeNow();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deletePurgeBatch(cutoffCaptor.capture(), eq(BATCH_SIZE));
        Instant cutoff = cutoffCaptor.getValue();

        // Cutoff should be roughly 90 days before "now", with a small slack window
        // for the time between `before` and the actual call.
        long daysBack = java.time.Duration.between(cutoff, before).toDays();
        assertTrue(daysBack >= 89 && daysBack <= 90,
                "cutoff should be ~90 days before invocation; was " + daysBack + " days back");
    }

    @Test
    @DisplayName("Configurable retention - 30-day setting passes through to cutoff calculation")
    void retentionConfigurable() {
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE))).thenReturn(0);

        service(30).purgeNow();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deletePurgeBatch(cutoffCaptor.capture(), eq(BATCH_SIZE));
        long daysBack = java.time.Duration.between(cutoffCaptor.getValue(), Instant.now()).toDays();
        assertTrue(daysBack >= 29 && daysBack <= 30);
    }

    @Test
    @DisplayName("Backlog larger than BATCH_SIZE × N still drains via repeated batched calls")
    void largeBacklogDrains() {
        // 5 batches of full size, then a final partial batch - 50_001 rows total.
        when(repository.deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE, BATCH_SIZE, BATCH_SIZE, BATCH_SIZE, BATCH_SIZE, 1);

        int total = service(90).purgeNow();

        assertEquals(50_001, total);
        verify(repository, times(6)).deletePurgeBatch(any(Instant.class), eq(BATCH_SIZE));
    }
}
