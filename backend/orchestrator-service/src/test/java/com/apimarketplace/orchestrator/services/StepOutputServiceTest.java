package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StepOutputService#loadPerItemNodeOutputs}, the durable
 * per-item output fallback used by {@code SplitAggregateHandler} when the in-memory
 * {@code SplitContext.resultsByNode} lacks a routed item's predecessor output
 * (split→aggregate cross-pod async-agent resume / post-restart - prod bug 2026-06-05).
 *
 * <p>Reads straight from the storage table (one query per node, item index + payload
 * in each row), so these tests stub {@link StorageRepository#findByRunIdAndEpochAndStepKey}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepOutputService.loadPerItemNodeOutputs")
class StepOutputServiceTest {

    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private StorageRepository storageRepository;
    @Mock private StorageSkeletonService storageSkeletonService;

    private StepOutputService service;

    @BeforeEach
    void setUp() {
        // Real ObjectMapper so the JSONB string is actually parsed.
        service = new StepOutputService(
            stepDataRepository, storageRepository, storageSkeletonService, new ObjectMapper());
    }

    /** A storage row mock carrying an item index + JSONB payload. */
    private StorageEntity row(Integer itemIndex, String dataJson) {
        StorageEntity row = mock(StorageEntity.class);
        lenient().when(row.getItemIndex()).thenReturn(itemIndex);
        lenient().when(row.getData()).thenReturn(dataJson);
        return row;
    }

    @Test
    @DisplayName("unwraps the persisted `output` wrapper and keys by split item index")
    void unwrapsOutputAndKeysByItemIndex() {
        StorageEntity r0 = row(0, "{\"output\":{\"subject\":\"Newsletter\"},\"graphNodeId\":\"core:parse_headers\"}");
        StorageEntity r1 = row(1, "{\"output\":{\"subject\":\"URGENT: server down\"},\"graphNodeId\":\"core:parse_headers\"}");
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 0, "core:parse_headers", "tenant-1"))
            .thenReturn(List.of(r0, r1));

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:parse_headers", 0, "tenant-1");

        assertEquals(2, out.size());
        assertEquals(Map.of("subject", "Newsletter"), out.get(0));
        assertEquals(Map.of("subject", "URGENT: server down"), out.get(1),
            "the inner output map must be returned (matching the in-memory slot shape)");
    }

    @Test
    @DisplayName("falls back to the whole data map when there is no `output` wrapper (defensive)")
    void noOutputWrapperReturnsWholeData() {
        StorageEntity r0 = row(0, "{\"subject\":\"flat\"}");
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 0, "core:x", "tenant-1"))
            .thenReturn(List.of(r0));

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:x", 0, "tenant-1");

        assertEquals(Map.of("subject", "flat"), out.get(0));
    }

    @Test
    @DisplayName("skips rows whose payload is blank")
    void skipsBlankData() {
        StorageEntity blank = row(0, "");
        StorageEntity ok = row(1, "{\"output\":{\"subject\":\"only one\"}}");
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 0, "core:parse_headers", "tenant-1"))
            .thenReturn(List.of(blank, ok));

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:parse_headers", 0, "tenant-1");

        assertEquals(1, out.size());
        assertFalse(out.containsKey(0));
        assertEquals(Map.of("subject", "only one"), out.get(1));
    }

    @Test
    @DisplayName("returns an empty map when no rows exist for the node/epoch")
    void emptyWhenNoRows() {
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 7, "core:parse_headers", "tenant-1"))
            .thenReturn(List.of());

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:parse_headers", 7, "tenant-1");

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("null itemIndex is treated as index 0")
    void nullItemIndexDefaultsToZero() {
        StorageEntity r = row(null, "{\"output\":{\"subject\":\"unindexed\"}}");
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 0, "core:parse_headers", "tenant-1"))
            .thenReturn(List.of(r));

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:parse_headers", 0, "tenant-1");

        assertEquals(Map.of("subject", "unindexed"), out.get(0));
    }

    @Test
    @DisplayName("latest row wins per item index (query returns oldest-first; loop rerun / spawn override)")
    void latestRowWinsPerItemIndex() {
        // Query orders createdAt ASC → the later (rerun/next-spawn) row for the same item
        // index comes last and must overwrite the earlier one.
        StorageEntity older = row(2, "{\"output\":{\"subject\":\"first attempt\"}}");
        StorageEntity newer = row(2, "{\"output\":{\"subject\":\"after rerun\"}}");
        when(storageRepository.findByRunIdAndEpochAndStepKey("run-1", 0, "core:parse_headers", "tenant-1"))
            .thenReturn(List.of(older, newer));

        Map<Integer, Object> out = service.loadPerItemNodeOutputs("run-1", "core:parse_headers", 0, "tenant-1");

        assertEquals(1, out.size());
        assertEquals(Map.of("subject", "after rerun"), out.get(2),
            "the latest row (last in createdAt-ASC order) must win per item index");
    }
}
