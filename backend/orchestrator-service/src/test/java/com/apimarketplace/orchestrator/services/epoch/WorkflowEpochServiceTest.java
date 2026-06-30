package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochCountRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowEpochService}.
 *
 * Covers: epoch 0 recording, triggerId propagation, prefix stripping,
 * multi-epoch accumulation, trigger-scoped queries, status normalization,
 * and epoch lifecycle (open/close/get with header JSONB).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochService")
class WorkflowEpochServiceTest {

    @Mock
    private WorkflowEpochRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private WorkflowEpochService service;

    private static final String RUN_ID = "run-abc-123";
    private static final String TRIGGER_ID = "trigger:my_webhook";

    @BeforeEach
    void setUp() {
        service = new WorkflowEpochService(repository, objectMapper);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordNodeCount
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordNodeCount")
    class RecordNodeCount {

        @Test
        @DisplayName("Epoch 0 records correctly (not lost)")
        void recordNodeCount_epoch0_recordsCorrectly() {
            service.recordNodeCount(RUN_ID, 0, "mcp:step1", "COMPLETED");

            verify(repository).upsert(RUN_ID, 0, "NODE", "mcp:step1", "COMPLETED");
        }

        @Test
        @DisplayName("With triggerId delegates to triggerId overload")
        void recordNodeCount_withTriggerId_delegatesToRepository() {
            service.recordNodeCount(RUN_ID, 1, "mcp:step1", "COMPLETED", TRIGGER_ID);

            verify(repository).upsert(RUN_ID, TRIGGER_ID, 1, "NODE", "mcp:step1", "COMPLETED");
        }

        @Test
        @DisplayName("Without triggerId uses default overload")
        void recordNodeCount_withoutTriggerId_usesDefault() {
            service.recordNodeCount(RUN_ID, 2, "mcp:step1", "FAILED");

            verify(repository).upsert(RUN_ID, 2, "NODE", "mcp:step1", "FAILED");
        }

        @Test
        @DisplayName("Null triggerId uses default overload")
        void recordNodeCount_nullTriggerId_usesDefault() {
            service.recordNodeCount(RUN_ID, 1, "mcp:step1", "COMPLETED", null);

            verify(repository).upsert(RUN_ID, 1, "NODE", "mcp:step1", "COMPLETED");
        }

        @Test
        @DisplayName("Normalizes SUCCESS to COMPLETED")
        void recordNodeCount_normalizesSuccess() {
            service.recordNodeCount(RUN_ID, 0, "mcp:step1", "SUCCESS");

            verify(repository).upsert(RUN_ID, 0, "NODE", "mcp:step1", "COMPLETED");
        }

        @Test
        @DisplayName("Normalizes ERROR to FAILED")
        void recordNodeCount_normalizesError() {
            service.recordNodeCount(RUN_ID, 0, "mcp:step1", "ERROR");

            verify(repository).upsert(RUN_ID, 0, "NODE", "mcp:step1", "FAILED");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordEdgeCounts
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordEdgeCounts")
    class RecordEdgeCounts {

        @Test
        @DisplayName("With triggerId delegates to triggerId overload")
        void recordEdgeCounts_withTriggerId() {
            Map<String, String> batch = Map.of("mcp:a->mcp:b", "COMPLETED");

            service.recordEdgeCounts(RUN_ID, 1, batch, TRIGGER_ID);

            verify(repository).upsertBatch(eq(RUN_ID), eq(TRIGGER_ID), eq(1), eq("EDGE"), anyMap());
        }

        @Test
        @DisplayName("Without triggerId uses default overload")
        void recordEdgeCounts_withoutTriggerId() {
            Map<String, String> batch = Map.of("mcp:a->mcp:b", "COMPLETED");

            service.recordEdgeCounts(RUN_ID, 0, batch);

            verify(repository).upsertBatch(eq(RUN_ID), eq(0), eq("EDGE"), anyMap());
        }

        @Test
        @DisplayName("Empty batch is no-op")
        void recordEdgeCounts_emptyBatch_isNoOp() {
            service.recordEdgeCounts(RUN_ID, 0, Map.of());

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("Null batch is no-op")
        void recordEdgeCounts_nullBatch_isNoOp() {
            service.recordEdgeCounts(RUN_ID, 0, null);

            verifyNoInteractions(repository);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAccumulatedNodeCounts
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAccumulatedNodeCounts")
    class GetAccumulatedNodeCounts {

        @Test
        @DisplayName("Strips prefix from keys")
        void getAccumulatedNodeCounts_stripsPrefixFromKeys() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 3),
                    new EpochCountRow("NODE", "trigger:webhook1", "COMPLETED", 2),
                    new EpochCountRow("NODE", "agent:classifier", "COMPLETED", 1),
                    new EpochCountRow("NODE", "core:check", "COMPLETED", 2)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedNodeCounts(RUN_ID);

            assertThat(result).containsKey("step1");
            assertThat(result).containsKey("webhook1");
            assertThat(result).containsKey("classifier");
            assertThat(result).containsKey("check");
            assertThat(result.get("step1").get("completed")).isEqualTo(3L);
            assertThat(result.get("webhook1").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Multi-epoch accumulation sums correctly")
        void getAccumulatedNodeCounts_multiEpochAccumulation() {
            // Repository already returns accumulated sums (GROUP BY)
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 5),
                    new EpochCountRow("NODE", "mcp:step1", "FAILED", 2)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedNodeCounts(RUN_ID);

            assertThat(result.get("step1").get("completed")).isEqualTo(5L);
            assertThat(result.get("step1").get("failed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Empty result returns empty map")
        void getAccumulatedNodeCounts_empty_returnsEmptyMap() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of());

            Map<String, Map<String, Long>> result = service.getAccumulatedNodeCounts(RUN_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Excludes EDGE entries from node counts")
        void getAccumulatedNodeCounts_excludesEdges() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 3),
                    new EpochCountRow("EDGE", "mcp:step1->mcp:step2", "COMPLETED", 3)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedNodeCounts(RUN_ID);

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("step1");
        }

        @Test
        @DisplayName("Merges duplicate labels from different prefixes")
        void getAccumulatedNodeCounts_mergesDuplicateLabels() {
            // Unlikely but possible: same label with different prefixes
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 2),
                    new EpochCountRow("NODE", "agent:step1", "COMPLETED", 1)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedNodeCounts(RUN_ID);

            // Both stripped to "step1", counts merged via Long::sum
            assertThat(result.get("step1").get("completed")).isEqualTo(3L);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAccumulatedEdgeCounts
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAccumulatedEdgeCounts")
    class GetAccumulatedEdgeCounts {

        @Test
        @DisplayName("Returns edge counts keyed verbatim by edge id (port-preserved)")
        void getAccumulatedEdgeCounts_keepsKeysVerbatim() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("EDGE", "trigger:scheduler->core:sharedmerge", "COMPLETED", 5),
                    new EpochCountRow("EDGE", "agent:classify:category_0->mcp:dispatch", "COMPLETED", 2)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedEdgeCounts(RUN_ID);

            assertThat(result.get("trigger:scheduler->core:sharedmerge").get("completed")).isEqualTo(5L);
            assertThat(result.get("agent:classify:category_0->mcp:dispatch").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Excludes NODE entries")
        void getAccumulatedEdgeCounts_excludesNodes() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 3),
                    new EpochCountRow("EDGE", "trigger:wh->mcp:step1", "COMPLETED", 3)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedEdgeCounts(RUN_ID);

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("trigger:wh->mcp:step1");
            assertThat(result).doesNotContainKey("mcp:step1");
        }

        @Test
        @DisplayName("Multi-trigger fan-in: only the fired trigger's edge appears")
        void getAccumulatedEdgeCounts_multiTriggerFanIn() {
            // Scheduler fires 5 times, ManualA never fires.
            // Only the scheduler edge is recorded - manuala edge has no row at all.
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("EDGE", "trigger:scheduler->core:sharedmerge", "COMPLETED", 5),
                    new EpochCountRow("EDGE", "core:sharedmerge->core:echo", "COMPLETED", 5)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedEdgeCounts(RUN_ID);

            assertThat(result.get("trigger:scheduler->core:sharedmerge").get("completed")).isEqualTo(5L);
            assertThat(result).doesNotContainKey("trigger:manuala->core:sharedmerge");
        }

        @Test
        @DisplayName("Empty repository result returns empty map")
        void getAccumulatedEdgeCounts_empty_returnsEmptyMap() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of());

            Map<String, Map<String, Long>> result = service.getAccumulatedEdgeCounts(RUN_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Sums multiple statuses for the same edge")
        void getAccumulatedEdgeCounts_sumsMultipleStatuses() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("EDGE", "trigger:wh->mcp:step1", "COMPLETED", 4),
                    new EpochCountRow("EDGE", "trigger:wh->mcp:step1", "SKIPPED", 1)
            ));

            Map<String, Map<String, Long>> result = service.getAccumulatedEdgeCounts(RUN_ID);

            assertThat(result.get("trigger:wh->mcp:step1").get("completed")).isEqualTo(4L);
            assertThat(result.get("trigger:wh->mcp:step1").get("skipped")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("getAccumulatedCounts (bundled)")
    class GetAccumulatedCountsBundled {

        @Test
        @DisplayName("Returns both nodes and edges from a single repository call")
        void returnsBothFromSingleCall() {
            when(repository.getAccumulatedCounts(RUN_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 3),
                    new EpochCountRow("EDGE", "trigger:wh->mcp:step1", "COMPLETED", 3)
            ));

            WorkflowEpochService.AccumulatedCounts result = service.getAccumulatedCounts(RUN_ID);

            assertThat(result.nodes().get("step1").get("completed")).isEqualTo(3L);
            assertThat(result.edges().get("trigger:wh->mcp:step1").get("completed")).isEqualTo(3L);

            // Single repository call powers both maps - avoids running the heavy
            // ACCUMULATED_COUNTS_SQL twice per /status-counts poll.
            verify(repository, times(1)).getAccumulatedCounts(RUN_ID);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getEpochState with triggerId
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trigger-scoped queries")
    class TriggerScopedQueries {

        @Test
        @DisplayName("getEpochState with triggerId filters correctly")
        void getEpochState_withTriggerId_filtersCorrectly() {
            when(repository.findByRunIdTriggerAndEpoch(RUN_ID, TRIGGER_ID, 0)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 1)
            ));

            Map<String, Object> result = service.getEpochState(RUN_ID, TRIGGER_ID, 0);

            assertThat(result.get("epoch")).isEqualTo(0);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Integer>> nodes = (Map<String, Map<String, Integer>>) result.get("nodes");
            assertThat(nodes).containsKey("mcp:step1");
            verify(repository).findByRunIdTriggerAndEpoch(RUN_ID, TRIGGER_ID, 0);
        }

        @Test
        @DisplayName("getAccumulatedState with triggerId filters correctly")
        void getAccumulatedState_withTriggerId_filtersCorrectly() {
            when(repository.getAccumulatedCountsByTrigger(RUN_ID, TRIGGER_ID)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 3)
            ));

            Map<String, Object> result = service.getAccumulatedState(RUN_ID, TRIGGER_ID);

            assertThat(result.get("epoch")).isEqualTo(-1);
            verify(repository).getAccumulatedCountsByTrigger(RUN_ID, TRIGGER_ID);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Epoch lifecycle: open / close / get
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Epoch lifecycle (header)")
    class EpochLifecycle {

        @Test
        @DisplayName("openEpoch serializes EpochState and calls upsertHeader")
        void openEpoch_serializesAndUpserts() {
            EpochState state = EpochState.fresh().markNodeCompleted("trigger:start");

            service.openEpoch(RUN_ID, TRIGGER_ID, 1, state);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).upsertHeader(eq(RUN_ID), eq(TRIGGER_ID), eq(1), jsonCaptor.capture());

            String json = jsonCaptor.getValue();
            assertThat(json).contains("trigger:start");
        }

        @Test
        @DisplayName("closeEpoch serializes final state and calls closeEpochHeader")
        void closeEpoch_serializesAndCloses() {
            EpochState finalState = EpochState.fresh()
                    .markNodeCompleted("trigger:start")
                    .markNodeCompleted("mcp:step1");

            service.closeEpoch(RUN_ID, TRIGGER_ID, 1, finalState);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).closeEpochHeader(eq(RUN_ID), eq(TRIGGER_ID), eq(1), jsonCaptor.capture(), eq(0L));

            String json = jsonCaptor.getValue();
            assertThat(json).contains("mcp:step1");
        }

        @Test
        @DisplayName("getFullEpochState deserializes header JSON correctly")
        void getFullEpochState_deserializesCorrectly() throws Exception {
            EpochState original = EpochState.fresh()
                    .markNodeCompleted("trigger:start")
                    .markNodeCompleted("mcp:step1")
                    .recordDecisionBranch("core:check", "if");
            String json = objectMapper.writeValueAsString(original);

            when(repository.getEpochHeader(RUN_ID, 1)).thenReturn(
                    new EpochHeaderRow(json, true, Instant.now(), null, TRIGGER_ID, null)
            );

            EpochState result = service.getFullEpochState(RUN_ID, 1);

            assertThat(result).isNotNull();
            assertThat(result.getCompletedNodeIds()).contains("trigger:start", "mcp:step1");
            assertThat(result.getDecisionBranchesMap().get("core:check")).contains("if");
        }

        @Test
        @DisplayName("getFullEpochState returns null when no header exists")
        void getFullEpochState_returnsNullWhenNoHeader() {
            when(repository.getEpochHeader(RUN_ID, 99)).thenReturn(null);

            EpochState result = service.getFullEpochState(RUN_ID, 99);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getFullEpochState with triggerId variant works")
        void getFullEpochState_withTriggerId() throws Exception {
            EpochState original = EpochState.fresh().markNodeCompleted("mcp:step1");
            String json = objectMapper.writeValueAsString(original);

            when(repository.getEpochHeader(RUN_ID, TRIGGER_ID, 2)).thenReturn(
                    new EpochHeaderRow(json, false, Instant.now(), Instant.now(), TRIGGER_ID, null)
            );

            EpochState result = service.getFullEpochState(RUN_ID, TRIGGER_ID, 2);

            assertThat(result).isNotNull();
            assertThat(result.getCompletedNodeIds()).contains("mcp:step1");
        }

        @Test
        @DisplayName("JSON round-trip preserves all EpochState fields")
        void roundTrip_preservesAllFields() throws Exception {
            EpochState original = EpochState.fresh()
                    .markNodeCompleted("trigger:start")
                    .markNodeFailed("mcp:bad_step")
                    .markNodeSkipped("mcp:skipped_step")
                    .addReadyNode("mcp:next_step")
                    .markNodeAwaitingSignal("core:wait")
                    .recordDecisionBranch("core:check", "else");

            String json = objectMapper.writeValueAsString(original);
            EpochState deserialized = objectMapper.readValue(json, EpochState.class);

            assertThat(deserialized.getCompletedNodeIds()).contains("trigger:start");
            assertThat(deserialized.getFailedNodeIds()).contains("mcp:bad_step");
            assertThat(deserialized.getSkippedNodeIds()).contains("mcp:skipped_step");
            assertThat(deserialized.getReadyNodeIds()).contains("mcp:next_step");
            assertThat(deserialized.getAwaitingSignalNodeIds()).contains("core:wait");
            assertThat(deserialized.getDecisionBranchesMap().get("core:check")).contains("else");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getEpochState enriched with header data
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getEpochState enriched with header")
    class GetEpochStateEnriched {

        @Test
        @DisplayName("Enriches result with header fields when header exists")
        void getEpochState_enrichesWithHeader() throws Exception {
            when(repository.findByRunIdAndEpoch(RUN_ID, 1)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 1)
            ));

            EpochState headerState = EpochState.fresh()
                    .markNodeCompleted("mcp:step1")
                    .addReadyNode("mcp:step2");
            String json = objectMapper.writeValueAsString(headerState);
            when(repository.getEpochHeader(RUN_ID, 1)).thenReturn(
                    new EpochHeaderRow(json, true, Instant.now(), null, TRIGGER_ID, null)
            );

            Map<String, Object> result = service.getEpochState(RUN_ID, 1);

            assertThat(result.get("isActive")).isEqualTo(true);
            assertThat(result.get("triggerId")).isEqualTo(TRIGGER_ID);
            @SuppressWarnings("unchecked")
            Set<String> readyNodes = (Set<String>) result.get("readyNodeIds");
            assertThat(readyNodes).contains("mcp:step2");
        }

        @Test
        @DisplayName("Returns basic state when no header exists")
        void getEpochState_basicWhenNoHeader() {
            when(repository.findByRunIdAndEpoch(RUN_ID, 1)).thenReturn(List.of(
                    new EpochCountRow("NODE", "mcp:step1", "COMPLETED", 1)
            ));
            when(repository.getEpochHeader(RUN_ID, 1)).thenReturn(null);

            Map<String, Object> result = service.getEpochState(RUN_ID, 1);

            assertThat(result.get("epoch")).isEqualTo(1);
            assertThat(result).doesNotContainKey("isActive");
            assertThat(result).doesNotContainKey("readyNodeIds");
        }
    }

    /**
     * P2.3 site 7 - Redis overlay for active-epoch runningNodeIds in the
     * diagnostic API. Pins the wiring contract so a regression that drops
     * the overlay (or runs it for closed epochs) breaks loudly.
     */
    @Nested
    @DisplayName("getEpochState - Redis overlay for active-epoch runningNodeIds (P2.3 site 7)")
    class GetEpochStateRedisOverlay {

        @Test
        @DisplayName("Active epoch: Redis running keys overlay JSONB (which is empty post-elide)")
        void activeEpoch_overlaysRedisOnEmptyJsonb() throws Exception {
            // Post-elide JSONB has runningNodeIds = empty for active epochs.
            // The overlay must surface what Redis holds so the diagnostic API
            // doesn't lie about active runs.
            EpochState elidedHeaderState = EpochState.fresh()
                    .markNodeCompleted("mcp:step1")
                    .addReadyNode("mcp:step3");
            String json = objectMapper.writeValueAsString(elidedHeaderState);
            when(repository.findByRunIdAndEpoch(RUN_ID, 5)).thenReturn(List.of());
            when(repository.getEpochHeader(RUN_ID, 5)).thenReturn(
                    new EpochHeaderRow(json, /*isActive=*/true, Instant.now(), null, TRIGGER_ID, null));

            // Wire a Redis tracker via reflection (field-injected, no setter).
            com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker tracker =
                    mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
            when(tracker.getRunningCounts(RUN_ID, 5)).thenReturn(
                    Map.of("mcp:step2", 1, "mcp:step4", 1));
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "runningNodeTracker", tracker);

            Map<String, Object> result = service.getEpochState(RUN_ID, 5);

            @SuppressWarnings("unchecked")
            Set<String> running = (Set<String>) result.get("runningNodeIds");
            assertThat(running).containsExactlyInAnyOrder("mcp:step2", "mcp:step4");
            verify(tracker).getRunningCounts(RUN_ID, 5);
        }

        @Test
        @DisplayName("Closed epoch: Redis tracker NOT consulted (running set already drained at close)")
        void closedEpoch_skipsRedisOverlay() throws Exception {
            EpochState closedHeaderState = EpochState.fresh()
                    .markNodeCompleted("mcp:step1")
                    .markNodeCompleted("mcp:step2");
            String json = objectMapper.writeValueAsString(closedHeaderState);
            when(repository.findByRunIdAndEpoch(RUN_ID, 3)).thenReturn(List.of());
            when(repository.getEpochHeader(RUN_ID, 3)).thenReturn(
                    new EpochHeaderRow(json, /*isActive=*/false, Instant.now(), Instant.now(), TRIGGER_ID, 12_000L));

            com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker tracker =
                    mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "runningNodeTracker", tracker);

            service.getEpochState(RUN_ID, 3);

            // Closed epoch - no Redis round-trip wasted.
            verify(tracker, never()).getRunningCounts(anyString(), anyInt());
        }

        @Test
        @DisplayName("Active epoch with no Redis tracker bean (legacy unit-test wiring) → falls back to JSONB cleanly")
        void activeEpoch_noTrackerBean_fallsBackToJsonb() throws Exception {
            // The default unit-test setUp doesn't inject runningNodeTracker -
            // it stays null. The overlay branch must be guarded so this case
            // doesn't NPE; it returns whatever JSONB has (empty for elided
            // tenants, populated for opt-out tenants).
            EpochState headerState = EpochState.fresh()
                    .markNodeCompleted("mcp:step1");
            String json = objectMapper.writeValueAsString(headerState);
            when(repository.findByRunIdAndEpoch(RUN_ID, 7)).thenReturn(List.of());
            when(repository.getEpochHeader(RUN_ID, 7)).thenReturn(
                    new EpochHeaderRow(json, /*isActive=*/true, Instant.now(), null, TRIGGER_ID, null));

            // No setField - runningNodeTracker stays null.
            Map<String, Object> result = service.getEpochState(RUN_ID, 7);

            // Falls back to JSONB. With elide-default-ON, this is empty for new
            // writes, but the contract is "no NPE, no exception".
            assertThat(result).containsKey("runningNodeIds");
        }

        @Test
        @DisplayName("Trigger-scoped overload also overlays Redis (same contract)")
        void triggerScopedOverload_overlaysRedis() throws Exception {
            EpochState headerState = EpochState.fresh()
                    .markNodeCompleted("mcp:done");
            String json = objectMapper.writeValueAsString(headerState);
            when(repository.findByRunIdTriggerAndEpoch(RUN_ID, TRIGGER_ID, 2)).thenReturn(List.of());
            when(repository.getEpochHeader(RUN_ID, TRIGGER_ID, 2)).thenReturn(
                    new EpochHeaderRow(json, /*isActive=*/true, Instant.now(), null, TRIGGER_ID, null));

            com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker tracker =
                    mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
            when(tracker.getRunningCounts(RUN_ID, 2)).thenReturn(Map.of("mcp:in_flight", 1));
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "runningNodeTracker", tracker);

            Map<String, Object> result = service.getEpochState(RUN_ID, TRIGGER_ID, 2);

            @SuppressWarnings("unchecked")
            Set<String> running = (Set<String>) result.get("runningNodeIds");
            assertThat(running).containsExactly("mcp:in_flight");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // normalizeStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizeStatus")
    class NormalizeStatus {

        @Test
        @DisplayName("SUCCESS -> COMPLETED")
        void normalizeSuccess() {
            assertThat(service.normalizeStatus("SUCCESS")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("ERROR -> FAILED")
        void normalizeError() {
            assertThat(service.normalizeStatus("ERROR")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("COMPLETED passes through")
        void normalizeCompleted() {
            assertThat(service.normalizeStatus("COMPLETED")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("null -> UNKNOWN")
        void normalizeNull() {
            assertThat(service.normalizeStatus(null)).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("Case insensitive")
        void normalizeCaseInsensitive() {
            assertThat(service.normalizeStatus("success")).isEqualTo("COMPLETED");
            assertThat(service.normalizeStatus("error")).isEqualTo("FAILED");
        }
    }
}
