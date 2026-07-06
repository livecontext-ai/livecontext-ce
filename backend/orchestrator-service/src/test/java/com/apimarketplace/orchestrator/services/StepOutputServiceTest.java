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

    /** A storage row mock carrying a step key + item index + JSONB payload. */
    private StorageEntity rowWithKey(String stepKey, Integer itemIndex, String dataJson) {
        StorageEntity row = row(itemIndex, dataJson);
        lenient().when(row.getStepKey()).thenReturn(stepKey);
        return row;
    }

    /** A storage row mock also carrying the ordering fields (createdAt, spawn, id). */
    private StorageEntity rowFull(String stepKey, Integer itemIndex, String dataJson,
            java.time.Instant createdAt, Integer spawn, java.util.UUID id) {
        StorageEntity row = rowWithKey(stepKey, itemIndex, dataJson);
        lenient().when(row.getCreatedAt()).thenReturn(createdAt);
        lenient().when(row.getSpawn()).thenReturn(spawn);
        lenient().when(row.getId()).thenReturn(id);
        return row;
    }

    @Test
    @DisplayName("loadPerItemOutputsByStepKey groups every node's per-item output by stepKey then item index")
    void loadPerItemOutputsByStepKeyGroups() {
        StorageEntity a0 = rowWithKey("core:parse_headers", 0, "{\"output\":{\"subject\":\"S0\"}}");
        StorageEntity a1 = rowWithKey("core:parse_headers", 1, "{\"output\":{\"subject\":\"S1\"}}");
        StorageEntity c0 = rowWithKey("agent:classify", 0, "{\"output\":{\"selected_category\":\"billing\"}}");
        StorageEntity c2 = rowWithKey("agent:classify", 2, "{\"output\":{\"selected_category\":\"refund_clear\"}}");
        StorageEntity blank = rowWithKey("core:noise", 0, "");
        when(storageRepository.findByRunIdAndEpoch("run-1", 0, "tenant-1"))
            .thenReturn(List.of(a0, a1, c0, c2, blank));

        Map<String, Map<Integer, Object>> out = service.loadPerItemOutputsByStepKey("run-1", 0, "tenant-1");

        assertEquals(Map.of("subject", "S0"), out.get("core:parse_headers").get(0));
        assertEquals(Map.of("subject", "S1"), out.get("core:parse_headers").get(1));
        assertEquals(Map.of("selected_category", "billing"), out.get("agent:classify").get(0));
        assertEquals(Map.of("selected_category", "refund_clear"), out.get("agent:classify").get(2));
        assertEquals(2, out.get("agent:classify").size(),
            "only items with a durable row are present (unrouted item 1 absent)");
        assertEquals(false, out.containsKey("core:noise"), "blank-payload rows are skipped");
    }

    @Test
    @DisplayName("loadPerItemOutputsByStepKey: latest spawn/rerun wins for the same stepKey+item")
    void loadPerItemOutputsByStepKeyLastWriteWins() {
        // Rows arrive createdAt ASC, so the later one overwrites the earlier for the same item.
        StorageEntity older = rowWithKey("agent:classify", 1, "{\"output\":{\"selected_category\":\"bug\"}}");
        StorageEntity newer = rowWithKey("agent:classify", 1, "{\"output\":{\"selected_category\":\"billing\"}}");
        when(storageRepository.findByRunIdAndEpoch("run-1", 0, "tenant-1"))
            .thenReturn(List.of(older, newer));

        Map<String, Map<Integer, Object>> out = service.loadPerItemOutputsByStepKey("run-1", 0, "tenant-1");

        assertEquals(Map.of("selected_category", "billing"), out.get("agent:classify").get(1));
    }

    @Test
    @DisplayName("loadPerItemOutputsByStepKey: latest write wins on a same-timestamp tie, independent of the query's id-DESC row order")
    void loadPerItemOutputsByStepKeyLatestWinsAcrossTiebreak() {
        // findByRunIdAndEpoch orders `createdAt, id DESC`, so a blind put()-last-wins over its raw
        // output would pick the LOWER id (older write) on a same-millisecond tie. The method re-sorts
        // ascending by (createdAt, spawn, id) so the later spawn/rerun wins - matching loadPerItemNodeOutputs.
        java.time.Instant t = java.time.Instant.parse("2026-07-05T00:00:00Z");
        StorageEntity older = rowFull("agent:classify", 1, "{\"output\":{\"selected_category\":\"OLD\"}}",
            t, 0, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        StorageEntity newer = rowFull("agent:classify", 1, "{\"output\":{\"selected_category\":\"NEW\"}}",
            t, 1, java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff"));
        // Deliberately supply the rows in the query's `id DESC` order (newer id first, older id last)
        // so a naive last-wins fold would wrongly pick OLD. The re-sort must still surface NEW.
        when(storageRepository.findByRunIdAndEpoch("run-1", 0, "tenant-1"))
            .thenReturn(List.of(newer, older));

        Map<String, Map<Integer, Object>> out = service.loadPerItemOutputsByStepKey("run-1", 0, "tenant-1");

        assertEquals(Map.of("selected_category", "NEW"), out.get("agent:classify").get(1),
            "the latest write (greatest spawn/id at equal createdAt) must win, not the query's last row");
    }

    @Test
    @DisplayName("loadPerItemOutputsByStepKey returns empty when the epoch has no durable rows")
    void loadPerItemOutputsByStepKeyEmpty() {
        when(storageRepository.findByRunIdAndEpoch("run-1", 3, "tenant-1")).thenReturn(List.of());
        assertEquals(Map.of(), service.loadPerItemOutputsByStepKey("run-1", 3, "tenant-1"));
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
