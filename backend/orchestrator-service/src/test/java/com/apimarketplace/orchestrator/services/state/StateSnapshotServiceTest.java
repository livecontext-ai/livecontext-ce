package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StateSnapshotService")
class StateSnapshotServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @Mock
    private com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher eventPublisher;

    @Mock
    private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;

    private StateSnapshotService service;

    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;
    private com.apimarketplace.orchestrator.metrics.WorkflowMetrics workflowMetrics;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        workflowMetrics = new com.apimarketplace.orchestrator.metrics.WorkflowMetrics(meterRegistry);
        service = new StateSnapshotService(runRepository, mapper, workflowEpochService, eventPublisher, breakdownService, new TxScopedSnapshotCache(runRepository, meterRegistry), workflowMetrics);
        // Plan v4 E2E5 - saveSnapshotFullRewrite now uses the native UPDATE
        // updateSnapshotAndSeq (was: runRepository.save(run)). Default-stub it
        // lenient(ly) to return 1 row so tests that exercise the legacy
        // full-rewrite path see a successful write; tests that never call it
        // don't trigger Mockito's strict-stub failure. Tests that need to
        // verify the actual seq / JSON written can re-stub or capture args.
        org.mockito.Mockito.lenient().when(
                        runRepository.updateSnapshotAndSeq(anyString(), anyString()))
                .thenReturn(1);
    }

    @Nested
    @DisplayName("parseSnapshotJson()")
    class ParseSnapshotJsonTests {

        @Test
        @DisplayName("Should return empty snapshot for null JSON")
        void shouldReturnEmptyForNull() {
            StateSnapshot result = service.parseSnapshotJson(null);

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().isEmpty());
            assertTrue(result.getFailedNodeIds().isEmpty());
            assertTrue(result.getSkippedNodeIds().isEmpty());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "\t"})
        @DisplayName("Should return empty snapshot for blank JSON")
        void shouldReturnEmptyForBlank(String json) {
            StateSnapshot result = service.parseSnapshotJson(json);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return empty snapshot for invalid JSON")
        void shouldReturnEmptyForInvalidJson() {
            StateSnapshot result = service.parseSnapshotJson("not valid json");

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should parse valid JSON with node IDs")
        void shouldParseValidJson() {
            String json = "{\"version\":1,\"completedNodeIds\":[\"mcp:step1\"],\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[\"mcp:step2\"],\"nodes\":{},\"edges\":{},\"decisionBranches\":{},\"loops\":{},\"splits\":{},\"awaitingSignalNodeIds\":[]}";

            StateSnapshot result = service.parseSnapshotJson(json);

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().contains("mcp:step1"));
            assertTrue(result.getReadyNodeIds().contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should parse H2 textual JSON snapshot")
        void shouldParseH2TextualJsonSnapshot() throws Exception {
            String innerJson = "{\"version\":3,\"completedNodeIds\":[\"mcp:step1\"],\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[\"mcp:step2\"],\"nodes\":{},\"edges\":{},\"decisionBranches\":{},\"loops\":{},\"splits\":{},\"awaitingSignalNodeIds\":[]}";
            String json = new ObjectMapper().writeValueAsString(innerJson);

            StateSnapshot result = service.parseSnapshotJson(json);

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().contains("mcp:step1"));
            assertTrue(result.getReadyNodeIds().contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should parse empty JSON object")
        void shouldParseEmptyJsonObject() {
            StateSnapshot result = service.parseSnapshotJson("{}");

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("getSnapshot()")
    class GetSnapshotTests {

        @Test
        @DisplayName("Should return empty snapshot when no run found")
        void shouldReturnEmptyWhenNoRunFound() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            StateSnapshot result = service.getSnapshot("run-1");

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().isEmpty());
        }

        @Test
        @DisplayName("Should return empty snapshot when snapshot JSON is blank")
        void shouldReturnEmptyWhenJsonBlank() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(""));

            StateSnapshot result = service.getSnapshot("run-1");

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().isEmpty());
        }

        @Test
        @DisplayName("A2 Phase 4 L1 cache hit: serves cached JSON without reading state_snapshot column when SQL seq matches (regression: avoid TOAST detoast on SSE hot path, audit Opus 2026-05-09)")
        void l1CacheHitSkipsStateSnapshotRead() {
            StateSnapshotJsonCache jsonCache = mock(StateSnapshotJsonCache.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "snapshotJsonCache", jsonCache);

            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-1")).thenReturn(Optional.of(7L));
            String cachedJson = "{\"version\":3,\"seq\":7,\"completedNodeIds\":[\"mcp:step1\"]," +
                    "\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[]," +
                    "\"awaitingSignalNodeIds\":[],\"nodes\":{},\"edges\":{},\"dags\":{}}";
            when(jsonCache.getPayloadIfMatchesSeq("run-1", 7L)).thenReturn(Optional.of(cachedJson));

            StateSnapshot result = service.getSnapshot("run-1");

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().contains("mcp:step1"));
            // L2 path skipped - the expensive JSONB read never fires.
            verify(runRepository, never()).findStateSnapshotByRunIdPublic("run-1");
            // No put-back on hit - the cache is already populated.
            verify(jsonCache, never()).putIfNewer(anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("A2 Phase 4 L1 miss → L2 DB read + populates cache via putIfNewer atomic Lua (regression: cache must self-warm on miss for subsequent SSE polls to hit)")
        void l1MissTriggersDbReadAndCachePopulation() {
            StateSnapshotJsonCache jsonCache = mock(StateSnapshotJsonCache.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "snapshotJsonCache", jsonCache);

            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-1")).thenReturn(Optional.of(7L));
            // Cache miss (e.g. seq mismatch / Redis cold)
            when(jsonCache.getPayloadIfMatchesSeq("run-1", 7L)).thenReturn(Optional.empty());
            String dbJson = "{\"version\":3,\"seq\":7,\"completedNodeIds\":[\"mcp:step2\"]," +
                    "\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[]," +
                    "\"awaitingSignalNodeIds\":[],\"nodes\":{},\"edges\":{},\"dags\":{}}";
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(dbJson));

            StateSnapshot result = service.getSnapshot("run-1");

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().contains("mcp:step2"));
            // Cache must be populated post-parse with the parsed snapshot's seq.
            verify(jsonCache).putIfNewer(eq("run-1"), eq(7L), eq(dbJson));
        }

        @Test
        @DisplayName("A2 Phase 4 M1: putIfNewer is deferred to AFTER commit when invoked from inside a tx (regression: synchronous populate-on-miss could persist a pre-commit seq into Redis, poisoning the cache if the tx rolls back - audit Opus A 2026-05-09)")
        void l2PopulateDeferredToAfterCommit() {
            StateSnapshotJsonCache jsonCache = mock(StateSnapshotJsonCache.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "snapshotJsonCache", jsonCache);

            when(runRepository.findStateSnapshotSeqByRunIdPublic("run-1")).thenReturn(Optional.of(7L));
            when(jsonCache.getPayloadIfMatchesSeq("run-1", 7L)).thenReturn(Optional.empty());
            String dbJson = "{\"version\":3,\"seq\":7,\"completedNodeIds\":[\"mcp:step3\"]," +
                    "\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[]," +
                    "\"awaitingSignalNodeIds\":[],\"nodes\":{},\"edges\":{},\"dags\":{}}";
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(dbJson));

            // Simulate an active outer tx (e.g. a @Transactional writer that calls
            // getSnapshot internally - V2StepByStepService, V2ExecutionEventService).
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                StateSnapshot result = service.getSnapshot("run-1");
                assertNotNull(result);

                // CRITICAL: putIfNewer must NOT have fired yet - pre-commit. Pre-fix
                // this assertion would fail (synchronous put inside the tx).
                verify(jsonCache, never()).putIfNewer(anyString(), anyLong(), anyString());

                // Drive afterCommit to simulate tx commit.
                var syncs = org.springframework.transaction.support.TransactionSynchronizationManager
                        .getSynchronizations();
                assertEquals(1, syncs.size(),
                        "expected exactly one afterCommit registration for the populate hook");
                for (var sync : syncs) {
                    sync.afterCommit();
                }

                // After commit, populate fires.
                verify(jsonCache).putIfNewer(eq("run-1"), eq(7L), eq(dbJson));
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("A2 Phase 4: cache wiring absent → falls through cleanly to DB+parse (zero behavior change for unit tests / pre-deploy)")
        void cacheNotWiredFallsThroughToDb() {
            // No snapshotJsonCache wired - service field stays null.
            String dbJson = "{\"version\":3,\"seq\":1,\"completedNodeIds\":[\"mcp:step1\"]," +
                    "\"failedNodeIds\":[],\"skippedNodeIds\":[],\"runningNodeIds\":[],\"readyNodeIds\":[]," +
                    "\"awaitingSignalNodeIds\":[],\"nodes\":{},\"edges\":{},\"dags\":{}}";
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(dbJson));

            StateSnapshot result = service.getSnapshot("run-1");

            assertNotNull(result);
            assertTrue(result.getCompletedNodeIds().contains("mcp:step1"));
            // Seq projection NEVER queried when cache is absent.
            verify(runRepository, never()).findStateSnapshotSeqByRunIdPublic(anyString());
        }
    }

    @Nested
    @DisplayName("hasSnapshot()")
    class HasSnapshotTests {

        @Test
        @DisplayName("Should return false when no run found")
        void shouldReturnFalseWhenNoRun() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertFalse(service.hasSnapshot("run-1"));
        }

        @Test
        @DisplayName("Should return false when snapshot JSON is null")
        void shouldReturnFalseWhenJsonNull() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.ofNullable(null));

            assertFalse(service.hasSnapshot("run-1"));
        }

        @Test
        @DisplayName("Should return false when snapshot JSON is blank")
        void shouldReturnFalseWhenJsonBlank() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(""));

            assertFalse(service.hasSnapshot("run-1"));
        }

        @Test
        @DisplayName("Should return true when snapshot JSON has content")
        void shouldReturnTrueWhenHasContent() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of("{}"));

            assertTrue(service.hasSnapshot("run-1"));
        }
    }

    @Nested
    @DisplayName("getEdgeCountsForPrePopulation()")
    class GetEdgeCountsTests {

        @Test
        @DisplayName("Should return empty map when no snapshot")
        void shouldReturnEmptyMapWhenNoSnapshot() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            var result = service.getEdgeCountsForPrePopulation("run-1");

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Convenience getter methods")
    class ConvenienceGetterTests {

        @Test
        @DisplayName("getCompletedNodeIds should return empty set when no snapshot")
        void shouldReturnEmptyCompletedNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getCompletedNodeIds("run-1").isEmpty());
        }

        @Test
        @DisplayName("getFailedNodeIds should return empty set when no snapshot")
        void shouldReturnEmptyFailedNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getFailedNodeIds("run-1").isEmpty());
        }

        @Test
        @DisplayName("getSkippedNodeIds should return empty set when no snapshot")
        void shouldReturnEmptySkippedNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getSkippedNodeIds("run-1").isEmpty());
        }

        @Test
        @DisplayName("getRunningNodeIds should return empty set when no snapshot")
        void shouldReturnEmptyRunningNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getRunningNodeIds("run-1").isEmpty());
        }

        @Test
        @DisplayName("getReadyNodeIds should return empty set when no snapshot")
        void shouldReturnEmptyReadyNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getReadyNodeIds("run-1").isEmpty());
        }

        @Test
        @DisplayName("getAwaitingSignalNodeIds should return empty set when no snapshot")
        void shouldReturnEmptyAwaitingSignalNodes() {
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertTrue(service.getAwaitingSignalNodeIds("run-1").isEmpty());
        }
    }

    @Nested
    @DisplayName("reconcileSbsRunStatus()")
    class ReconcileSbsRunStatusTests {

        private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        private WorkflowRunEntity createRun(String runId, RunStatus status, String snapshotJson) {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setStatus(status);
            run.setStateSnapshot(snapshotJson);
            return run;
        }

        /**
         * Build a realistic snapshot via JSON round-trip (immutable API).
         * Uses DAG-scoped methods to build proper DagState + EpochState.
         */
        private String buildSnapshotJson(String triggerId, Set<String> completed,
                                          Set<String> ready) throws Exception {
            // Build via immutable StateSnapshot API
            StateSnapshot s = StateSnapshot.empty();
            for (String nodeId : completed) {
                s = s.markNodeCompleted(triggerId, nodeId);
            }
            for (String nodeId : ready) {
                s = s.addReadyNode(triggerId, nodeId);
            }
            return mapper.writeValueAsString(s);
        }

        @Test
        @DisplayName("Phase 1: After trigger fires, mcp:step1 is READY → PAUSED")
        void shouldTransitionToPausedWhenNonTriggerReady() throws Exception {
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook"),
                    Set.of("mcp:step1"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.PAUSED, run.getStatus());
            verify(runRepository).save(run);
            // Epoch must NOT be closed - workflow still in progress
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        @Test
        @DisplayName("Phase 2: After step1 executes, mcp:step2 is READY → still PAUSED")
        void shouldRemainPausedWhenMoreNonTriggerNodesReady() throws Exception {
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1"),
                    Set.of("mcp:step2"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.PAUSED, run.getStatus());
            verify(runRepository).save(run);
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        @Test
        @DisplayName("Phase 3: After last node, only trigger:webhook READY → WAITING_TRIGGER (epoch stays open for rerun)")
        void shouldTransitionToWaitingTriggerWithoutClosingEpoch() throws Exception {
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1", "mcp:step2"),
                    Set.of("trigger:webhook"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus());
            verify(runRepository, atLeast(1)).save(run);
            // Epoch must NOT be closed - user may want to rerun the last node.
            // Epoch is closed when the NEXT trigger fires (in executeTriggerInternal → closeAllActiveEpochs).
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
            // WS event must be emitted
            verify(eventPublisher).emitWorkflowStatus(eq("run-1"), eq("waiting_trigger"),
                    eq("SBS status reconciled"), any(), eq(false));
        }

        @Test
        @DisplayName("Should NOT update status when already at desired state")
        void shouldNotUpdateWhenAlreadyAtDesiredState() throws Exception {
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook"),
                    Set.of("mcp:step1"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.PAUSED, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            verify(runRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should emit WS event even when status unchanged (for idempotent frontend sync)")
        void shouldAlwaysEmitWsEvent() throws Exception {
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook"),
                    Set.of("mcp:step1"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            verify(eventPublisher).emitWorkflowStatus(eq("run-1"), eq("paused"),
                    eq("SBS status reconciled"), any(), eq(false));
        }

        @Test
        @DisplayName("Full SBS lifecycle: 3 phases → PAUSED → PAUSED → WAITING_TRIGGER (no epoch close)")
        void fullSbsLifecycleThreePhases() throws Exception {
            // === Phase 1: trigger fires, step1 ready → PAUSED ===
            String json1 = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook"),
                    Set.of("mcp:step1"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json1);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json1));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.PAUSED, run.getStatus(), "Phase 1: PAUSED (step1 ready)");

            // === Phase 2: step1 done, step2 ready → still PAUSED ===
            String json2 = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1"),
                    Set.of("mcp:step2"));
            run.setStatus(RunStatus.RUNNING);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json2));

            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.PAUSED, run.getStatus(), "Phase 2: PAUSED (step2 ready)");

            // === Phase 3: step2 done (last node), only trigger ready → WAITING_TRIGGER ===
            String json3 = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1", "mcp:step2"),
                    Set.of("trigger:webhook"));
            run.setStatus(RunStatus.RUNNING);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json3));

            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "Phase 3: WAITING_TRIGGER (only trigger ready)");
            // Epoch stays open for potential rerun - closed when next trigger fires
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        @Test
        @DisplayName("Multi-DAG: one DAG complete, other has non-trigger ready → PAUSED")
        void multiDagPartialComplete() throws Exception {
            // DAG A: all done, only trigger ready
            // DAG B: step_b1 still ready (non-trigger)
            StateSnapshot s = StateSnapshot.empty();
            s = s.markNodeCompleted("trigger:webhook_a", "trigger:webhook_a");
            s = s.markNodeCompleted("trigger:webhook_a", "mcp:step_a1");
            s = s.addReadyNode("trigger:webhook_a", "trigger:webhook_a");

            s = s.markNodeCompleted("trigger:webhook_b", "trigger:webhook_b");
            s = s.addReadyNode("trigger:webhook_b", "mcp:step_b1");

            String json = mapper.writeValueAsString(s);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.PAUSED, run.getStatus(),
                    "PAUSED: DAG B has non-trigger ready node");
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(), anyLong());
        }

        @Test
        @DisplayName("Multi-DAG: both DAGs complete, both triggers ready → WAITING_TRIGGER (epochs stay open)")
        void multiDagBothComplete() throws Exception {
            StateSnapshot s = StateSnapshot.empty();
            s = s.markNodeCompleted("trigger:webhook_a", "trigger:webhook_a");
            s = s.markNodeCompleted("trigger:webhook_a", "mcp:step_a1");
            s = s.addReadyNode("trigger:webhook_a", "trigger:webhook_a");

            s = s.markNodeCompleted("trigger:webhook_b", "trigger:webhook_b");
            s = s.markNodeCompleted("trigger:webhook_b", "mcp:step_b1");
            s = s.addReadyNode("trigger:webhook_b", "trigger:webhook_b");

            String json = mapper.writeValueAsString(s);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "WAITING_TRIGGER: both DAGs only have trigger ready");
            // Epochs stay open for potential rerun - closed when next trigger fires
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        @Test
        @DisplayName("WAITING_TRIGGER: epoch stays open (user can rerun last node)")
        void epochStaysOpenInWaitingTrigger() throws Exception {
            // After last node completes → WAITING_TRIGGER, but epoch should stay open.
            // This allows the user to rerun the last node before firing a new trigger.
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1", "mcp:step2"),
                    Set.of("trigger:webhook"));

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus());

            // Key assertion: epoch is NOT closed - no calls to closeEpoch
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());

            // The snapshot should still have the epoch active (not modified by reconcile)
            // Epoch closure is deferred to next trigger fire (closeAllActiveEpochs)
        }

        @Test
        @DisplayName("WAITING_TRIGGER with awaiting signal: should stay PAUSED")
        void shouldStayPausedWhenAwaitingSignal() throws Exception {
            // After user approval node: node is awaiting signal, no non-trigger ready nodes.
            // Without the awaiting signal check, it would go WAITING_TRIGGER and close epochs.
            StateSnapshot s = StateSnapshot.empty();
            s = s.markNodeCompleted("trigger:webhook", "trigger:webhook");
            s = s.markNodeCompleted("trigger:webhook", "mcp:step1");
            // approval node is awaiting signal - in awaitingSignalNodeIds flat view
            s = s.markNodeAwaitingSignal("trigger:webhook", "core:approval");
            // trigger is ready for next fire (from prepareNextEpochReady)
            s = s.addReadyNode("trigger:webhook", "trigger:webhook");

            String json = mapper.writeValueAsString(s);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.PAUSED, run.getStatus(),
                    "Should stay PAUSED when a node is awaiting signal");
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        @Test
        @DisplayName("Completed trigger re-added to readySteps → WAITING_TRIGGER")
        void completedTriggerShouldBeReAddedToReadyAndTransitionToWaitingTrigger() throws Exception {
            // All nodes completed, nothing ready - but SBS re-adds completed trigger.
            // In SBS, epochs stay open, so a completed trigger should be available for re-fire.
            String json = buildSnapshotJson("trigger:webhook",
                    Set.of("trigger:webhook", "mcp:step1"),
                    Set.of());

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(json));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "SBS re-adds completed trigger to readySteps → WAITING_TRIGGER");
        }
    }

    /**
     * End-to-end test simulating the real InternalSbsController flow:
     * mergeReadyNodesAfterExecution() → reconcileSbsRunStatus() at each step.
     *
     * Workflow: trigger:webhook → mcp:step1 → mcp:step2 (3 nodes, linear DAG)
     * Expected: PAUSED after trigger, PAUSED after step1, WAITING_TRIGGER after step2
     */
    @Nested
    @DisplayName("E2E: SBS merge + reconcile flow")
    class E2eSbsMergeAndReconcileTests {

        private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        private WorkflowRunEntity createRun(String runId, RunStatus status, String snapshotJson) {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setStatus(status);
            run.setStateSnapshot(snapshotJson);
            return run;
        }

        @Test
        @DisplayName("E2E: trigger → step1 → step2 → all transitions correct")
        void fullE2eLinearWorkflow() throws Exception {
            // Initial state: trigger:webhook is READY (workflow just started)
            StateSnapshot initial = StateSnapshot.empty()
                    .addReadyNode("trigger:webhook");
            String initialJson = mapper.writeValueAsString(initial);

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, initialJson);

            // === STEP 1: Execute trigger:webhook → mcp:step1 becomes ready ===
            // mergeReadyNodesAfterExecution: remove trigger:webhook from ready, add mcp:step1
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            service.mergeReadyNodesAfterExecution("run-1", "trigger:webhook", Set.of("mcp:step1"));

            // After merge, snapshot should have: completed={trigger:webhook}, ready={mcp:step1}
            StateSnapshot afterTrigger = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterTrigger.getReadyNodeIds().contains("mcp:step1"),
                    "After trigger: mcp:step1 should be READY");
            assertFalse(afterTrigger.getReadyNodeIds().contains("trigger:webhook"),
                    "After trigger: trigger:webhook should NOT be READY");

            // reconcileSbsRunStatus: mcp:step1 is non-trigger ready → PAUSED
            String afterTriggerJson = run.getStateSnapshot();
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(afterTriggerJson));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.PAUSED, run.getStatus(), "After trigger execution: should be PAUSED");

            // === STEP 2: Execute mcp:step1 → mcp:step2 becomes ready ===
            run.setStatus(RunStatus.RUNNING); // simulates execution starting
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            service.mergeReadyNodesAfterExecution("run-1", "mcp:step1", Set.of("mcp:step2"));

            StateSnapshot afterStep1 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterStep1.getReadyNodeIds().contains("mcp:step2"),
                    "After step1: mcp:step2 should be READY");
            assertFalse(afterStep1.getReadyNodeIds().contains("mcp:step1"),
                    "After step1: mcp:step1 should NOT be READY");

            // reconcileSbsRunStatus: mcp:step2 is non-trigger ready → PAUSED
            String afterStep1Json = run.getStateSnapshot();
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(afterStep1Json));
            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.PAUSED, run.getStatus(), "After step1 execution: should be PAUSED");

            // === STEP 3: Execute mcp:step2 (last node) → trigger:webhook becomes ready ===
            run.setStatus(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            service.mergeReadyNodesAfterExecution("run-1", "mcp:step2", Set.of("trigger:webhook"));

            StateSnapshot afterStep2 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterStep2.getReadyNodeIds().contains("trigger:webhook"),
                    "After step2: trigger:webhook should be READY");
            assertFalse(afterStep2.getReadyNodeIds().contains("mcp:step2"),
                    "After step2: mcp:step2 should NOT be READY");
            assertEquals(1, afterStep2.getReadyNodeIds().size(),
                    "After step2: only trigger:webhook should be READY");

            // reconcileSbsRunStatus: only trigger ready → WAITING_TRIGGER (epoch stays open for rerun)
            String afterStep2Json = run.getStateSnapshot();
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(afterStep2Json));
            service.reconcileSbsRunStatus("run-1");
            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "After last node: should be WAITING_TRIGGER");
            // Epoch stays open - closed when next trigger fires
            verify(workflowEpochService, never()).closeEpoch(anyString(), anyString(),
                    anyInt(), any(EpochState.class), anyLong());
        }

        /**
         * This test reproduces the REAL SBS refire bug and validates the new behavior:
         *
         * 1st webhook fire (epoch 1):
         *   openEpoch(1) → trigger executes → prepareNextEpochReady(trigger) →
         *   step executes → reconcileSbsRunStatus → WAITING_TRIGGER (epoch stays open)
         *
         * 2nd webhook fire (epoch 2):
         *   openEpoch(2) → trigger executes → prepareNextEpochReady(trigger) →
         *   step executes → reconcileSbsRunStatus → WAITING_TRIGGER (epoch stays open)
         *
         * Epoch closure now happens when the NEXT trigger fires
         * (executeTriggerInternal → closeAllActiveEpochs), not in reconcileSbsRunStatus.
         * This allows the user to rerun any node, including the last one.
         */
        @Test
        @DisplayName("E2E SBS refire: epoch 1 complete → epoch 2 complete → both WAITING_TRIGGER")
        void sbsRefireFullCycle() throws Exception {
            String TRG = "trigger:webhook";
            String STEP = "mcp:step1";

            // === EPOCH 1: First webhook fire ===

            // 1a. openEpoch(1) - creates active epoch 1
            StateSnapshot s = StateSnapshot.empty();
            String json = mapper.writeValueAsString(s);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            service.openEpoch("run-1", TRG, 1);

            // Verify epoch 1 is active
            StateSnapshot afterOpen1 = service.parseSnapshotJson(run.getStateSnapshot());
            assertNotNull(afterOpen1.getDags().get(TRG), "DAG should exist after openEpoch");
            assertTrue(afterOpen1.getDags().get(TRG).getActiveEpochs().contains(1),
                    "Epoch 1 should be in activeEpochs");

            // 1b. Trigger executes: merge removes trigger from ready, adds step
            service.mergeReadyNodesAfterExecution("run-1", TRG, 1, TRG, Set.of(STEP));

            // 1c. prepareNextEpochReady: adds trigger back to ready for next fire
            service.prepareNextEpochReady("run-1", TRG);

            // Verify trigger AND step are in ready
            StateSnapshot afterPrepare1 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterPrepare1.getReadyNodeIds().contains(STEP),
                    "Step should be READY after trigger execution");
            assertTrue(afterPrepare1.getReadyNodeIds().contains(TRG),
                    "Trigger should be READY after prepareNextEpochReady (epoch 1)");

            // 1d. Step executes: merge removes step from ready, no successors
            service.mergeReadyNodesAfterExecution("run-1", TRG, 1, STEP, Set.of());

            // Only trigger should remain in ready
            StateSnapshot afterStep1 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterStep1.getReadyNodeIds().contains(TRG),
                    "Only trigger should remain READY after last step");
            assertFalse(afterStep1.getReadyNodeIds().contains(STEP),
                    "Executed step should NOT be READY");

            // 1e. reconcileSbsRunStatus → WAITING_TRIGGER (epoch stays open for potential rerun)
            when(runRepository.findStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(run.getStateSnapshot()));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "Epoch 1 done: should be WAITING_TRIGGER");

            // Epoch stays open - trigger is still in readyNodeIds (flat view from active epoch)
            StateSnapshot afterReconcile1 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterReconcile1.getReadyNodeIds().contains(TRG),
                    "Trigger should remain READY in flat view (epoch stays open)");

            // === EPOCH 2: Second webhook fire (refire) ===

            // 2a. openEpoch(2) - creates active epoch 2
            run.setStatus(RunStatus.RUNNING);
            service.openEpoch("run-1", TRG, 2);

            StateSnapshot afterOpen2 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterOpen2.getDags().get(TRG).getActiveEpochs().contains(2),
                    "Epoch 2 should be in activeEpochs after openEpoch");
            // currentEpoch should be 2 (important for prepareNextEpochReady targeting)
            assertEquals(2, afterOpen2.getDags().get(TRG).getCurrentEpoch(),
                    "currentEpoch should be 2 after openEpoch(2)");

            // 2b. Trigger executes: merge removes trigger from ready, adds step
            service.mergeReadyNodesAfterExecution("run-1", TRG, 2, TRG, Set.of(STEP));

            // 2c. prepareNextEpochReady: adds trigger back for next fire
            //     BUG scenario: if currentEpoch was 3 (from phantom resetDagWithRerunPattern),
            //     trigger would go to epoch 3 (dormant, not in activeEpochs) → flat view empty
            service.prepareNextEpochReady("run-1", TRG);

            StateSnapshot afterPrepare2 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterPrepare2.getReadyNodeIds().contains(STEP),
                    "Step should be READY after trigger execution (epoch 2)");
            assertTrue(afterPrepare2.getReadyNodeIds().contains(TRG),
                    "Trigger should be READY after prepareNextEpochReady (epoch 2). " +
                    "If this fails, prepareNextEpochReady targeted wrong epoch!");

            // 2d. Step executes: merge removes step, no successors
            service.mergeReadyNodesAfterExecution("run-1", TRG, 2, STEP, Set.of());

            StateSnapshot afterStep2 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterStep2.getReadyNodeIds().contains(TRG),
                    "Only trigger should remain READY after last step (epoch 2)");

            // 2e. reconcileSbsRunStatus → WAITING_TRIGGER again
            when(runRepository.findStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(run.getStateSnapshot()));
            service.reconcileSbsRunStatus("run-1");

            assertEquals(RunStatus.WAITING_TRIGGER, run.getStatus(),
                    "Epoch 2 done: should be WAITING_TRIGGER, NOT PAUSED. " +
                    "If PAUSED, the trigger wasn't in readyNodes - prepareNextEpochReady " +
                    "targeted wrong epoch (phantom epoch from resetDagWithRerunPattern).");

            // Verify trigger remains ready in flat view after second WAITING_TRIGGER
            StateSnapshot afterReconcile2 = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterReconcile2.getReadyNodeIds().contains(TRG),
                    "Trigger should remain READY in flat view after second WAITING_TRIGGER");
        }

        /**
         * Proves the root cause: if DagState.currentEpoch is advanced beyond the active
         * epoch (e.g., by resetDagWithRerunPattern creating a phantom epoch), then
         * prepareNextEpochReady targets the wrong (dormant) epoch. The trigger ends up
         * invisible in flat views → reconcileSbsRunStatus falls back to PAUSED.
         *
         * This test SIMULATES what resetDagWithRerunPattern did: advance currentEpoch
         * via prepareDagForNextCycle, then verify prepareNextEpochReady breaks.
         */
        @Test
        @DisplayName("BUG PROOF: phantom epoch causes prepareNextEpochReady to target wrong epoch")
        void phantomEpochBreaksPrepareNextEpochReady() throws Exception {
            String TRG = "trigger:webhook";
            String STEP = "mcp:step1";

            // Start with epoch 2 active (simulating second webhook fire)
            StateSnapshot s = StateSnapshot.empty().openEpochForDag(TRG, 2);
            String json = mapper.writeValueAsString(s);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, json);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Verify epoch 2 is active, currentEpoch = 2
            StateSnapshot before = service.parseSnapshotJson(run.getStateSnapshot());
            assertEquals(2, before.getDags().get(TRG).getCurrentEpoch());
            assertTrue(before.getDags().get(TRG).getActiveEpochs().contains(2));

            // SIMULATE THE BUG: prepareDagForNextCycle advances currentEpoch to 3 (phantom)
            // This is what resetDagWithRerunPattern used to do
            StateSnapshot withPhantom = before.prepareDagForNextCycle(TRG, 3, TRG);
            run.setStateSnapshot(mapper.writeValueAsString(withPhantom));

            // Verify phantom state: currentEpoch=3, but epoch 3 is NOT in activeEpochs
            StateSnapshot phantom = service.parseSnapshotJson(run.getStateSnapshot());
            assertEquals(3, phantom.getDags().get(TRG).getCurrentEpoch(),
                    "currentEpoch should be 3 (phantom)");
            assertFalse(phantom.getDags().get(TRG).getActiveEpochs().contains(3),
                    "Epoch 3 should NOT be in activeEpochs (dormant)");
            assertTrue(phantom.getDags().get(TRG).getActiveEpochs().contains(2),
                    "Epoch 2 should still be active");

            // Now call prepareNextEpochReady - it uses addReadyNode(triggerId, triggerId)
            // which targets currentEpochState() = epoch 3 (the phantom)
            service.prepareNextEpochReady("run-1", TRG);

            // The trigger should be in readyNodeIds (flat view).
            // BUG: flat view only includes activeEpochs. Epoch 3 is dormant → trigger invisible!
            StateSnapshot afterPrepare = service.parseSnapshotJson(run.getStateSnapshot());
            Set<String> readyFlat = afterPrepare.getReadyNodeIds();

            // This assertion PROVES the bug: trigger is NOT in flat ready view
            // because it was added to dormant epoch 3, not active epoch 2.
            // In the FIXED code (no resetDagWithRerunPattern in SBS), this scenario
            // doesn't happen. But this test documents WHY the fix is necessary.
            assertFalse(readyFlat.contains(TRG),
                    "BUG PROOF: trigger added to phantom epoch 3 (dormant) is invisible " +
                    "in flat view. This is why reconcileSbsRunStatus returned PAUSED.");

            // Verify the trigger IS in epoch 3 (just not visible in flat view)
            var epoch3State = afterPrepare.getDags().get(TRG).getEpochState(3);
            assertNotNull(epoch3State, "Phantom epoch 3 should exist");
            assertTrue(epoch3State.getReadyNodeIds().contains(TRG),
                    "Trigger IS in epoch 3 ready set - just not in flat view");
        }

        @Test
        @DisplayName("E2E: each reconcile correctly sees updated snapshot from merge (no stale reads)")
        void noStaleReadsBetweenMergeAndReconcile() throws Exception {
            // Start with trigger ready
            StateSnapshot initial = StateSnapshot.empty()
                    .addReadyNode("trigger:webhook");
            String initialJson = mapper.writeValueAsString(initial);
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING, initialJson);

            // Execute trigger → step1 ready
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            service.mergeReadyNodesAfterExecution("run-1", "trigger:webhook", Set.of("mcp:step1"));

            // KEY: reconcile reads from the SAME run entity that merge just updated
            String mergedJson = run.getStateSnapshot();
            when(runRepository.findStateSnapshotByRunIdPublic("run-1")).thenReturn(Optional.of(mergedJson));

            // Verify the readyNodeIds seen by reconcile
            Set<String> readyFromSnapshot = service.getReadyNodeIds("run-1");
            assertTrue(readyFromSnapshot.contains("mcp:step1"),
                    "Reconcile must see the merged ready nodes, not stale data");
            assertFalse(readyFromSnapshot.contains("trigger:webhook"),
                    "Executed trigger should be removed from ready");
        }
    }

    @Nested
    @DisplayName("mergeReadyNodesAfterExecution()")
    class MergeReadyNodesTests {

        private WorkflowRunEntity createRunWithSnapshot(String runId, String snapshotJson) {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setStateSnapshot(snapshotJson);
            return run;
        }

        @Test
        @DisplayName("Should merge new ready nodes while preserving existing ones from other DAGs")
        void shouldMergeAndPreserveOtherDagReadyNodes() throws Exception {
            // Snapshot has DAG1 node_a2 READY and DAG2 trigger:webhook_b READY
            String snapshotJson = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(
                StateSnapshot.empty()
                    .addReadyNode("mcp:node_a2")
                    .addReadyNode("trigger:webhook_b")
            );
            WorkflowRunEntity run = createRunWithSnapshot("run-1", snapshotJson);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Execute trigger:webhook_b, new ready = {mcp:node_b1}
            service.mergeReadyNodesAfterExecution("run-1", "trigger:webhook_b", Set.of("mcp:node_b1"));

            // Plan v4 E2E5 - verify the native updateSnapshotAndSeq UPDATE was called
            verify(runRepository).updateSnapshotAndSeq(eq("run-1"), anyString());

            // Parse the saved snapshot (still mirrored to the entity for in-tx coherence)
            StateSnapshot saved = service.parseSnapshotJson(run.getStateSnapshot());

            // DAG1's node_a2 must be preserved
            assertTrue(saved.getReadyNodeIds().contains("mcp:node_a2"),
                "DAG1 ready node must be preserved");
            // DAG2's new node_b1 must be added
            assertTrue(saved.getReadyNodeIds().contains("mcp:node_b1"),
                "New ready node must be added");
            // Executed node must be removed
            assertFalse(saved.getReadyNodeIds().contains("trigger:webhook_b"),
                "Executed node must be removed");
            assertEquals(2, saved.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("Should remove executed node even when new ready set is empty")
        void shouldRemoveExecutedNodeWhenNoNewReady() throws Exception {
            // Last node in DAG, no successors
            String snapshotJson = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(
                StateSnapshot.empty()
                    .addReadyNode("mcp:node_a2")
                    .addReadyNode("mcp:last_node")
            );
            WorkflowRunEntity run = createRunWithSnapshot("run-1", snapshotJson);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Execute mcp:last_node, no successors
            service.mergeReadyNodesAfterExecution("run-1", "mcp:last_node", Set.of());

            verify(runRepository).updateSnapshotAndSeq(eq("run-1"), anyString());

            StateSnapshot saved = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(saved.getReadyNodeIds().contains("mcp:node_a2"));
            assertFalse(saved.getReadyNodeIds().contains("mcp:last_node"));
            assertEquals(1, saved.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("Should handle empty initial ready set")
        void shouldHandleEmptyInitialReadySet() throws Exception {
            String snapshotJson = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(
                StateSnapshot.empty()
            );
            WorkflowRunEntity run = createRunWithSnapshot("run-1", snapshotJson);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            service.mergeReadyNodesAfterExecution("run-1", "trigger:start", Set.of("mcp:step1", "mcp:step2"));

            verify(runRepository).updateSnapshotAndSeq(eq("run-1"), anyString());

            StateSnapshot saved = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(saved.getReadyNodeIds().contains("mcp:step1"));
            assertTrue(saved.getReadyNodeIds().contains("mcp:step2"));
            assertEquals(2, saved.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("Should do nothing when run not found")
        void shouldDoNothingWhenRunNotFound() {
            when(runRepository.findByRunIdPublicForUpdate("run-x")).thenReturn(Optional.empty());

            service.mergeReadyNodesAfterExecution("run-x", "mcp:node", Set.of("mcp:next"));

            verify(runRepository, never()).save(any());
        }

        @Test
        @DisplayName("Full multi-DAG scenario: DAG2 fires while DAG1 has ready node")
        void fullMultiDagScenarioThroughService() throws Exception {
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());

            // Initial state: DAG1 advanced to node_a2 (READY), DAG2 trigger is READY
            StateSnapshot initial = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook_a")
                .markNodeCompleted("mcp:node_a1")
                .addReadyNode("mcp:node_a2")
                .addReadyNode("trigger:webhook_b");

            WorkflowRunEntity run = createRunWithSnapshot("run-1", mapper.writeValueAsString(initial));
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // DAG2 trigger executes, successor is mcp:node_b1
            service.mergeReadyNodesAfterExecution("run-1", "trigger:webhook_b", Set.of("mcp:node_b1"));

            StateSnapshot afterTrigger = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterTrigger.getReadyNodeIds().contains("mcp:node_a2"),
                "DAG1 node_a2 must remain READY after DAG2 trigger fires");
            assertTrue(afterTrigger.getReadyNodeIds().contains("mcp:node_b1"),
                "DAG2 node_b1 must be READY");
            assertFalse(afterTrigger.getReadyNodeIds().contains("trigger:webhook_b"),
                "Fired trigger must be removed from READY");

            // Now DAG2 step executes: mcp:node_b1 -> mcp:node_b2
            run.setStateSnapshot(mapper.writeValueAsString(afterTrigger));
            service.mergeReadyNodesAfterExecution("run-1", "mcp:node_b1", Set.of("mcp:node_b2"));

            StateSnapshot afterStep = service.parseSnapshotJson(run.getStateSnapshot());
            assertTrue(afterStep.getReadyNodeIds().contains("mcp:node_a2"),
                "DAG1 node_a2 must STILL be READY after DAG2 advances");
            assertTrue(afterStep.getReadyNodeIds().contains("mcp:node_b2"),
                "DAG2 node_b2 must be READY");
            assertFalse(afterStep.getReadyNodeIds().contains("mcp:node_b1"),
                "Executed node_b1 must be removed");
            assertEquals(2, afterStep.getReadyNodeIds().size());
        }
    }

    @Nested
    @DisplayName("Per-transaction parse cache (TxScopedSnapshotCache integration)")
    class TxScopedParseCacheIntegration {

        @org.junit.jupiter.api.AfterEach
        void clearTxState() {
            // Defense-in-depth: even if a test forgot to clean up, drop any residual
            // bound resource for our cache key so the next test starts fresh.
            // TransactionSynchronizationManager.clear() does NOT release resources.
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .hasResource(TxScopedSnapshotCache.class)) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .unbindResource(TxScopedSnapshotCache.class);
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }

        @Test
        @DisplayName("Inside an active tx, the same runId is parsed by Jackson EXACTLY ONCE across N reads")
        void parsesOncePerTxForSameRunId() throws Exception {
            // Spy on the ObjectMapper so we can count readValue() invocations.
            ObjectMapper realMapper = new ObjectMapper();
            realMapper.registerModule(new JavaTimeModule());
            ObjectMapper spyMapper = spy(realMapper);

            TxScopedSnapshotCache cache = new TxScopedSnapshotCache(runRepository, meterRegistry);
            StateSnapshotService cachedService = new StateSnapshotService(
                runRepository, spyMapper, workflowEpochService, eventPublisher, breakdownService, cache, workflowMetrics);

            String runId = "run-cache-test";
            StateSnapshot snapshot = StateSnapshot.empty().withIncrementedSeq();
            String json = realMapper.writeValueAsString(snapshot);
            org.mockito.Mockito.lenient()
                    .when(runRepository.findStateSnapshotSeqByRunIdPublic(runId))
                    .thenReturn(Optional.of(snapshot.getSeq()));

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                StateSnapshot first = cachedService.parseSnapshotForRun(runId, json);
                StateSnapshot second = cachedService.parseSnapshotForRun(runId, json);
                StateSnapshot third = cachedService.parseSnapshotForRun(runId, json);

                assertSame(first, second, "Cache hit must return the same instance");
                assertSame(first, third, "Cache hit must return the same instance");
                verify(spyMapper, times(1)).readValue(eq(json), eq(StateSnapshot.class));
            } finally {
                // Fire afterCompletion so OUR synchronization's unbind hook runs -
                // TransactionSynchronizationManager.clear() does NOT touch resources.
                for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCompletion(0);
                }
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
                org.springframework.transaction.support.TransactionSynchronizationManager.clear();
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(TxScopedSnapshotCache.class)) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .unbindResource(TxScopedSnapshotCache.class);
                }
            }
        }

        @Test
        @DisplayName("Outside any tx, parseSnapshotForRun falls through to Jackson on every call (no caching)")
        void noCachingOutsideTx() throws Exception {
            ObjectMapper realMapper = new ObjectMapper();
            realMapper.registerModule(new JavaTimeModule());
            ObjectMapper spyMapper = spy(realMapper);

            StateSnapshotService cachedService = new StateSnapshotService(
                runRepository, spyMapper, workflowEpochService, eventPublisher, breakdownService,
                new TxScopedSnapshotCache(runRepository, meterRegistry), workflowMetrics);

            String json = realMapper.writeValueAsString(StateSnapshot.empty().withIncrementedSeq());

            cachedService.parseSnapshotForRun("run-X", json);
            cachedService.parseSnapshotForRun("run-X", json);

            // Without an active transaction we degrade to direct parse - Jackson is
            // called every time. This is the correct behavior: no row lock = no
            // cache invariant.
            verify(spyMapper, times(2)).readValue(eq(json), eq(StateSnapshot.class));
        }

        @Test
        @DisplayName("After parseSnapshotForRun, the cache contains the EXACT instance returned (not just count=1)")
        void cacheHoldsSameInstanceAfterParse() throws Exception {
            // Regression guard: a refactor that broke `txCache.put(runId, parsed)` in
            // parseSnapshotForRun would still pass `verify(times(1))` if the second
            // call coincidentally hit a different code path. Asserting instance
            // identity locks the contract.
            ObjectMapper realMapper = new ObjectMapper();
            realMapper.registerModule(new JavaTimeModule());
            TxScopedSnapshotCache cache = new TxScopedSnapshotCache(runRepository, meterRegistry);
            StateSnapshotService cachedService = new StateSnapshotService(
                runRepository, realMapper, workflowEpochService, eventPublisher, breakdownService, cache, workflowMetrics);

            String runId = "run-cache-state-test";
            StateSnapshot snapshot = StateSnapshot.empty().withIncrementedSeq();
            String json = realMapper.writeValueAsString(snapshot);

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                StateSnapshot parsed = cachedService.parseSnapshotForRun(runId, json);
                org.mockito.Mockito.lenient()
                        .when(runRepository.findStateSnapshotSeqByRunIdPublic(runId))
                        .thenReturn(Optional.of(parsed.getSeq()));

                assertSame(parsed, cache.get(runId).orElseThrow(),
                    "The cache must hold the exact instance returned by parseSnapshotForRun");
            } finally {
                for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCompletion(0);
                }
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
                org.springframework.transaction.support.TransactionSynchronizationManager.clear();
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(TxScopedSnapshotCache.class)) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .unbindResource(TxScopedSnapshotCache.class);
                }
            }
        }

        @Test
        @DisplayName("After saveSnapshot, getSnapshot returns the post-mutation value even if DB still holds the old JSON")
        void saveSnapshotMakesNextReadSeeLatest() throws Exception {
            // Regression guard for the saveSnapshot → cache.put contract. If saveSnapshot
            // forgot to refresh the cache, an in-tx getSnapshot would return the stale
            // pre-mutation snapshot from DB.
            ObjectMapper realMapper = new ObjectMapper();
            realMapper.registerModule(new JavaTimeModule());
            TxScopedSnapshotCache cache = new TxScopedSnapshotCache(runRepository, meterRegistry);
            StateSnapshotService cachedService = new StateSnapshotService(
                runRepository, realMapper, workflowEpochService, eventPublisher, breakdownService, cache, workflowMetrics);

            String runId = "run-save-cache-test";
            // DB always returns an EMPTY snapshot - only the cache will reflect the mutation.
            // The test asserts the DB is NEVER hit (verify never), so the stub is the
            // safety net for any accidental fallback. Use lenient() because Mockito's
            // strict mode would otherwise flag the unused-stubbing as an error - here
            // the stub IS deliberately unused (that's the whole point of the test).
            String emptyJson = realMapper.writeValueAsString(StateSnapshot.empty().withIncrementedSeq());
            org.mockito.Mockito.lenient()
                .when(runRepository.findStateSnapshotByRunIdPublic(runId))
                .thenReturn(Optional.of(emptyJson));

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                // Simulate a mutation that has already run inside this tx and put a
                // distinguishable snapshot into the cache (the way saveSnapshot would).
                StateSnapshot mutated = StateSnapshot.empty()
                    .addReadyNode("trigger:start").withIncrementedSeq();
                cache.put(runId, mutated);
                org.mockito.Mockito.lenient()
                        .when(runRepository.findStateSnapshotSeqByRunIdPublic(runId))
                        .thenReturn(Optional.of(mutated.getSeq()));

                StateSnapshot fromGet = cachedService.getSnapshot(runId);

                assertTrue(fromGet.getReadyNodeIds().contains("trigger:start"),
                    "getSnapshot must return the post-mutation cached value, not the stale DB value");
                assertSame(mutated, fromGet,
                    "getSnapshot must return the exact cached instance");
                verify(runRepository, never()).findStateSnapshotByRunIdPublic(runId);
            } finally {
                for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCompletion(0);
                }
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
                org.springframework.transaction.support.TransactionSynchronizationManager.clear();
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(TxScopedSnapshotCache.class)) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .unbindResource(TxScopedSnapshotCache.class);
                }
            }
        }

        @Test
        @DisplayName("parseSnapshotForRun(null, json) bypasses the cache and calls Jackson directly")
        void nullRunIdBypassesCache() throws Exception {
            ObjectMapper realMapper = new ObjectMapper();
            realMapper.registerModule(new JavaTimeModule());
            ObjectMapper spyMapper = spy(realMapper);

            TxScopedSnapshotCache cache = new TxScopedSnapshotCache(runRepository, meterRegistry);
            StateSnapshotService cachedService = new StateSnapshotService(
                runRepository, spyMapper, workflowEpochService, eventPublisher, breakdownService, cache, workflowMetrics);

            String json = realMapper.writeValueAsString(StateSnapshot.empty().withIncrementedSeq());

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                cachedService.parseSnapshotForRun(null, json);
                cachedService.parseSnapshotForRun(null, json);

                verify(spyMapper, times(2)).readValue(eq(json), eq(StateSnapshot.class));
                assertEquals(0, cache.size(), "null runId must NOT pollute the cache");
            } finally {
                // Fire afterCompletion so OUR synchronization's unbind hook runs -
                // TransactionSynchronizationManager.clear() does NOT touch resources.
                for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCompletion(0);
                }
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
                org.springframework.transaction.support.TransactionSynchronizationManager.clear();
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(TxScopedSnapshotCache.class)) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .unbindResource(TxScopedSnapshotCache.class);
                }
            }
        }
    }

    /**
     * P2.0 baseline instrumentation: verify saveSnapshot writes a Micrometer counter labeled
     * by the originating mutator, plus a bytes histogram and latency histogram.
     *
     * <p>Without this baseline, the doc's §3.6 claim that running-related saves account for
     * 15-25% of total saveSnapshot calls cannot be falsified, and the post-purge target
     * (§3.7) has no anchor. Per the project docs rev8 P2.0.
     */
    @Nested
    @DisplayName("P2.0 instrumentation: saveSnapshot mutator-labeled counter + bytes + latency")
    class StateSnapshotInstrumentationTests {

        private WorkflowRunEntity stubRun(String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getStateSnapshot()).thenReturn(null);
            return run;
        }

        @Test
        @DisplayName("Each public mutator emits a counter increment tagged with its method name")
        void counterTaggedByMutator() {
            String runId = "run-instr-1";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            // Trigger 3 distinct mutators on the same run.
            service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1");
            service.markNodeFailed(runId, "trigger:t1", 0, "mcp:n2");
            service.addReadyNode(runId, "mcp:n3");

            // Each mutator produces a counter row tagged by its name.
            assertEquals(1.0,
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                            .tag("mutator", "marknodecompleted")
                            .counter().count(),
                    "markNodeCompleted should emit one counter increment");
            assertEquals(1.0,
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                            .tag("mutator", "marknodefailed")
                            .counter().count(),
                    "markNodeFailed should emit one counter increment");
            assertEquals(1.0,
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                            .tag("mutator", "addreadynode")
                            .counter().count(),
                    "addReadyNode should emit one counter increment");
        }

        @Test
        @DisplayName("Repeated calls of the same mutator increment the same counter row")
        void counterAccumulatesPerMutator() {
            String runId = "run-instr-2";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            for (int i = 0; i < 5; i++) {
                service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n" + i);
            }

            assertEquals(5.0,
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                            .tag("mutator", "marknodecompleted")
                            .counter().count(),
                    "5 calls of markNodeCompleted should sum to 5");
        }

        @Test
        @DisplayName("Bytes histogram captures the size of the persisted JSON")
        void bytesHistogramRecorded() {
            String runId = "run-instr-3";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1");

            io.micrometer.core.instrument.DistributionSummary bytes =
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_BYTES)
                            .tag("mutator", "marknodecompleted")
                            .summary();
            assertEquals(1L, bytes.count(), "exactly one bytes observation per save");
            assertTrue(bytes.totalAmount() > 0,
                    "bytes written must be > 0 (the snapshot JSON is non-empty)");
        }

        @Test
        @DisplayName("Latency histogram is recorded in milliseconds, non-negative")
        void latencyHistogramRecorded() {
            String runId = "run-instr-4";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1");

            io.micrometer.core.instrument.DistributionSummary latency =
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_LATENCY_MS)
                            .tag("mutator", "marknodecompleted")
                            .summary();
            assertEquals(1L, latency.count(), "exactly one latency observation per save");
            assertTrue(latency.totalAmount() >= 0.0,
                    "latency must be non-negative (System.nanoTime() delta ≥ 0)");
        }

        @Test
        @DisplayName("No metric is recorded when the run lookup misses (no save executed)")
        void noMetricWhenRunMissing() {
            String runId = "run-missing";
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.empty());

            service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1");

            // No counter row should exist for this mutator (the .find() should return null).
            io.micrometer.core.instrument.search.Search search = meterRegistry
                    .find(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "marknodecompleted");
            assertNull(search.counter(),
                    "saveSnapshot must NOT be called when the run is missing - no metric expected");
        }

        @Test
        @DisplayName("Bytes histogram total matches the persisted JSON byte length (contract pin)")
        void bytesEqualsPersistedJsonLength() {
            String runId = "run-bytes-contract";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            // Capture what was actually written to the entity. saveSnapshot calls
            // run.setStateSnapshot(json), so the captor receives the persisted JSON.
            org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

            service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1");
            verify(run).setStateSnapshot(jsonCaptor.capture());

            long expectedBytes = jsonCaptor.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            io.micrometer.core.instrument.DistributionSummary bytes =
                    meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_BYTES)
                            .tag("mutator", "marknodecompleted")
                            .summary();
            assertEquals(expectedBytes, (long) bytes.totalAmount(),
                    "bytes histogram total must equal the actual persisted JSON byte length");
        }

        @Test
        @DisplayName("Cardinality bound: 3 mutators × 4 calls = 3 distinct counter rows summing to 12")
        void cardinalityBoundedAcrossMutators() {
            String runId = "run-cardinality";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            for (int i = 0; i < 4; i++) {
                service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n" + i);
                service.markNodeFailed(runId, "trigger:t1", 0, "mcp:n" + i);
                service.addReadyNode(runId, "mcp:r" + i);
            }

            // Three distinct counter rows, each at exactly 4.
            assertEquals(4.0, meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "marknodecompleted").counter().count());
            assertEquals(4.0, meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "marknodefailed").counter().count());
            assertEquals(4.0, meterRegistry.get(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "addreadynode").counter().count());

            // Total counter rows for save_count metric: exactly 3 (one per mutator).
            // Sum of all rows: 12. If a refactor accidentally collapsed two mutators into
            // one tag value, we'd see 2 rows summing to 12 - caught here.
            long totalRows = meterRegistry.find(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .counters().size();
            assertEquals(3, totalRows, "exactly 3 distinct mutator tag values expected");

            double totalSum = meterRegistry.find(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
            assertEquals(12.0, totalSum, "3 mutators × 4 calls = 12 total saves");
        }

        @Test
        @DisplayName("JsonProcessingException on serialize records NO metric (regression - saveSnapshot rolls back the metric on serializer failure)")
        void noMetricOnSerializerFailure() throws Exception {
            // Regression guard: a refactor that emits the metric BEFORE the catch block,
            // or that moves dbWriteSucceeded above the throw, would silently count failed
            // saves as successes - poisoning the P2.0 baseline.
            String runId = "run-serializer-fail";
            WorkflowRunEntity run = stubRun(runId);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            // Replace the service's ObjectMapper with one that throws on writeValueAsString.
            // Using reflection because mapper is final/ctor-injected.
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any())).thenThrow(
                    new com.fasterxml.jackson.core.JsonGenerationException("forced for test", (com.fasterxml.jackson.core.JsonGenerator) null));
            java.lang.reflect.Field mapperField = StateSnapshotService.class.getDeclaredField("objectMapper");
            mapperField.setAccessible(true);
            mapperField.set(service, failingMapper);

            // Triggering the mutator must throw RuntimeException (wrapping the JsonProcessingException).
            assertThrows(RuntimeException.class, () -> service.markNodeCompleted(runId, "trigger:t1", 0, "mcp:n1"));

            // Critical: NO metric row should exist for "marknodecompleted" - the failure
            // path is NOT counted as a successful save.
            io.micrometer.core.instrument.search.Search search = meterRegistry
                    .find(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "marknodecompleted");
            assertNull(search.counter(),
                    "JsonProcessingException must NOT increment the save counter - only durably-persisted saves count");
        }

        @Test
        @DisplayName("claimNodeForExecution rejected (node not in readyNodeIds) records no metric")
        void claimRejectedRecordsNoMetric() {
            String runId = "run-claim-rejected";
            WorkflowRunEntity run = stubRun(runId);
            // empty snapshot → readyNodeIds is empty → claim rejected before saveSnapshot
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean claimed = service.claimNodeForExecution(runId, "mcp:not-ready");
            assertFalse(claimed, "claim must be rejected when nodeId is not in readyNodeIds");

            // Rejected claims must NOT count toward the save metric (no save was executed).
            io.micrometer.core.instrument.search.Search search = meterRegistry
                    .find(com.apimarketplace.orchestrator.metrics.WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                    .tag("mutator", "claimnodeforexecution");
            assertNull(search.counter(),
                    "rejected claim must NOT increment the save counter - only successful saves count");
        }
    }

    /**
     * Plan v4 E2E4 - regression guard against Hibernate L1 returning a stale
     * entity after {@code findByRunIdPublicForUpdate}.
     *
     * <p>Scenario surfaced by k6 saturation (50 VU × 3 min on a single run):
     * <pre>
     *   tx start
     *     ReusableTriggerService.executeTriggerInternal:
     *        run = runRepository.findByRunIdPublic(runId)   // NO lock - entity in L1
     *        ... uses run.getStatus() / etc.
     *     StateSnapshotService.openEpoch(runId, ...):
     *        runRepository.findByRunIdPublicForUpdate(runId) // SELECT ... FOR UPDATE
     *        - Hibernate sees the entity in L1, returns the SAME instance with
     *           the stale state_snapshot from the no-lock load. DB lock IS
     *           acquired; the data we read is stale.
     *        snapshot = parseSnapshot(run)               // stale JSON
     *        updated = snapshot.openEpochForDag(...).withIncrementedSeq() // stale_seq+1
     *        runRepository.save(run)                     // UPDATE seq = stale_seq+1
     *        V181 trigger: ERROR state_snapshot_seq must not regress (was=N, new=stale_seq+1)
     *   tx rollback
     * </pre>
     *
     * <p>Fix: {@code loadFreshForUpdate} wraps the repo call with
     * {@code entityManager.refresh(entity)}, forcing Hibernate to re-fetch the
     * row from DB after acquiring the lock. The entity reflects live state,
     * not the L1 copy.
     */
    @Nested
    @DisplayName("E2E4 - loadFreshForUpdate refreshes entity to defeat L1 staleness")
    class LoadFreshForUpdateContract {

        jakarta.persistence.EntityManager mockEntityManager;
        WorkflowRunEntity run;

        @BeforeEach
        void setUp() {
            mockEntityManager = mock(jakarta.persistence.EntityManager.class);
            run = new WorkflowRunEntity();
            run.setRunIdPublic("run-e2e4");
            run.setTenantId("tenant-e2e4");
            run.setStateSnapshot("{\"seq\":10}");
            run.setStateSnapshotSeq(10L);

            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "entityManager", mockEntityManager);
        }

        @Test
        @DisplayName("helper invokes entityManager.refresh on the locked entity")
        void refreshesAfterLock() {
            when(runRepository.findByRunIdPublicForUpdate("run-e2e4"))
                    .thenReturn(Optional.of(run));

            // Drive through initializeSnapshot - simplest path. Run already has a
            // non-null snapshot so the if-branch inside initializeSnapshot is
            // skipped; we only exercise the load+refresh.
            service.initializeSnapshot("run-e2e4");

            // The contract: lock acquired, THEN refresh called. Order matters -
            // refresh must precede any read of run.getStateSnapshot().
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(runRepository, mockEntityManager);
            inOrder.verify(runRepository).findByRunIdPublicForUpdate("run-e2e4");
            inOrder.verify(mockEntityManager).refresh(run);
        }

        @Test
        @DisplayName("helper is null-safe when entityManager is not injected")
        void nullEntityManagerIsSafe() {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "entityManager", null);
            when(runRepository.findByRunIdPublicForUpdate("run-e2e4"))
                    .thenReturn(Optional.of(run));

            // Production always has @PersistenceContext; ~80 unit tests in this
            // file build the service without it. Must not NPE.
            assertDoesNotThrow(() -> service.initializeSnapshot("run-e2e4"));
        }

        @Test
        @DisplayName("helper does not call refresh when the run is absent")
        void absentRunNoRefresh() {
            when(runRepository.findByRunIdPublicForUpdate("missing"))
                    .thenReturn(Optional.empty());

            service.initializeSnapshot("missing");

            verify(mockEntityManager, never()).refresh(any());
        }
    }

    @Nested
    @DisplayName("resetDagAndSetReady - multi-trigger DAG targeting (rerun)")
    class ResetDagAndSetReadyDagTargeting {

        /** Builds a 2-DAG snapshot: trigger:a owns mcp:a_step, trigger:b owns mcp:b_step. */
        private StateSnapshot multiDagSnapshot() {
            return StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .markNodeCompleted("trigger:a", "mcp:a_step")
                    .markNodeCompleted("trigger:b", "trigger:b")
                    .markNodeCompleted("trigger:b", "mcp:b_step");
        }

        private WorkflowRunEntity stubRunWithSnapshot(String runId, StateSnapshot snapshot) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(snapshot));
            return run;
        }

        private StateSnapshot capturePersisted(WorkflowRunEntity run) {
            org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(run).setStateSnapshot(jsonCaptor.capture());
            return service.parseSnapshotJson(jsonCaptor.getValue());
        }

        @Test
        @DisplayName("with ownerTriggerId: READY marker lands in the owner trigger's DAG, not an arbitrary one")
        void readyMarkerLandsInOwnerDag() throws Exception {
            // Regression: flat addReadyNode(nodeId) resolved the DAG via getDefaultTriggerId(),
            // which picks an arbitrary real trigger when the snapshot has multiple DAGs.
            String runId = "run-multidag-rerun";
            WorkflowRunEntity run = stubRunWithSnapshot(runId, multiDagSnapshot());
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            service.resetDagAndSetReady(runId, Set.of("mcp:b_step"), "mcp:b_step", "trigger:b");

            StateSnapshot persisted = capturePersisted(run);
            assertTrue(persisted.getEpochState("trigger:b").getReadyNodeIds().contains("mcp:b_step"),
                    "READY marker must land in the owner trigger's (trigger:b) epoch state");
            assertFalse(persisted.getEpochState("trigger:a").getReadyNodeIds().contains("mcp:b_step"),
                    "READY marker must NOT land in the sibling DAG (trigger:a)");
            // The reset must have removed the node from trigger:b's completed set.
            assertFalse(persisted.getEpochState("trigger:b").getCompletedNodeIds().contains("mcp:b_step"),
                    "reset node must no longer be completed in its own DAG");
            // Unrelated DAG state preserved.
            assertTrue(persisted.getEpochState("trigger:a").getCompletedNodeIds().contains("mcp:a_step"),
                    "sibling DAG's completed nodes must be preserved");
        }

        @Test
        @DisplayName("without ownerTriggerId (3-arg legacy): falls back to flat default-DAG resolution")
        void legacyThreeArgFallsBackToFlatResolution() throws Exception {
            // Single-DAG snapshot: the flat fallback is unambiguous and must keep working.
            String runId = "run-singledag-rerun";
            StateSnapshot singleDag = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .markNodeCompleted("trigger:a", "mcp:a_step");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, singleDag);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            service.resetDagAndSetReady(runId, Set.of("mcp:a_step"), "mcp:a_step");

            StateSnapshot persisted = capturePersisted(run);
            assertTrue(persisted.getEpochState("trigger:a").getReadyNodeIds().contains("mcp:a_step"),
                    "single-DAG flat fallback must mark the node ready in the only DAG");
            assertFalse(persisted.getEpochState("trigger:a").getCompletedNodeIds().contains("mcp:a_step"),
                    "reset node must no longer be completed");
        }
    }

    @Nested
    @DisplayName("releaseNodeClaimIfUnresolved - SBS stale-claim recovery")
    class ReleaseNodeClaimIfUnresolved {

        private WorkflowRunEntity stubRunWithSnapshot(String runId, StateSnapshot snapshot) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(snapshot));
            return run;
        }

        private StateSnapshot capturePersisted(WorkflowRunEntity run) {
            org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(run).setStateSnapshot(jsonCaptor.capture());
            return service.parseSnapshotJson(jsonCaptor.getValue());
        }

        @Test
        @DisplayName("releases a claimed-but-unresolved node back to READY (regression: stuck NODE_NOT_READY)")
        void releasesUnresolvedRunningNode() throws Exception {
            // Regression: InternalSbsController claims (READY → RUNNING), the async execution
            // throws before the engine records any outcome → node stuck forever.
            String runId = "run-release-1";
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .addRunningNode("trigger:a", "mcp:step");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "mcp:step");

            assertTrue(released, "unresolved running node must be released");
            StateSnapshot persisted = capturePersisted(run);
            assertTrue(persisted.getEpochState("trigger:a").getReadyNodeIds().contains("mcp:step"),
                    "node must be READY again in its own DAG");
            assertFalse(persisted.getEpochState("trigger:a").getRunningNodeIds().contains("mcp:step"),
                    "node must no longer be RUNNING");
        }

        @Test
        @DisplayName("no-op when the node reached a terminal state (engine already owned the outcome)")
        void noopWhenNodeResolved() throws Exception {
            String runId = "run-release-2";
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .markNodeFailed("trigger:a", "mcp:step");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "mcp:step");

            assertFalse(released, "a FAILED node must NOT be re-readied (engine owns the outcome)");
            verify(run, never()).setStateSnapshot(anyString());
        }

        @Test
        @DisplayName("no-op when the node is awaiting a signal")
        void noopWhenAwaitingSignal() throws Exception {
            String runId = "run-release-3";
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .markNodeAwaitingSignal("trigger:a", "core:approval");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "core:approval");

            assertFalse(released, "an awaiting-signal node must NOT be re-readied");
            verify(run, never()).setStateSnapshot(anyString());
        }

        @Test
        @DisplayName("prod-default (running elided to Redis): flat fallback still re-readies the node")
        void elidedRunningFallsBackToFlatReady() throws Exception {
            // In prod the running-elide flag is default-ON: claimNodeForExecution's RUNNING
            // entry never reaches JSONB, so the DAG-scan finds nothing and the flat
            // addReadyNode fallback is the PRIMARY release path - the node (nowhere in the
            // snapshot: not ready, not running, not resolved) must still become READY again.
            String runId = "run-release-5";
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "mcp:step");

            assertTrue(released, "claim must be released via the flat fallback when running is elided");
            StateSnapshot persisted = capturePersisted(run);
            assertTrue(persisted.getReadyNodeIds().contains("mcp:step"),
                    "node must appear in the flat READY union (drives the FE play button and the claim check)");
        }

        @Test
        @DisplayName("no-op when the node is already READY (nothing to release)")
        void noopWhenAlreadyReady() throws Exception {
            String runId = "run-release-6";
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .addReadyNode("trigger:a", "mcp:step");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "mcp:step");

            assertFalse(released, "already-READY node needs no release");
            verify(run, never()).setStateSnapshot(anyString());
        }

        @Test
        @DisplayName("preserves loop state of the released node (release must not be a removeNodes)")
        void preservesLoopStateOnRelease() throws Exception {
            // A loop node mid-iteration may be re-claimed for its next SBS click; if that
            // click dies, the release must keep its LoopState intact.
            String runId = "run-release-4";
            StateSnapshot.LoopState midLoop = StateSnapshot.LoopState.initial(5).nextIteration();
            StateSnapshot snapshot = StateSnapshot.empty()
                    .markNodeCompleted("trigger:a", "trigger:a")
                    .updateLoopState("trigger:a", "core:my_loop", midLoop)
                    .addRunningNode("trigger:a", "core:my_loop");
            WorkflowRunEntity run = stubRunWithSnapshot(runId, snapshot);
            when(runRepository.findByRunIdPublicForUpdate(runId)).thenReturn(Optional.of(run));

            boolean released = service.releaseNodeClaimIfUnresolved(runId, "core:my_loop");

            assertTrue(released);
            StateSnapshot persisted = capturePersisted(run);
            assertNotNull(persisted.getEpochState("trigger:a").getLoopState("core:my_loop"),
                    "LoopState must survive the claim release");
            assertEquals(midLoop.currentIndex(),
                    persisted.getEpochState("trigger:a").getLoopState("core:my_loop").currentIndex(),
                    "iteration counter must be preserved");
        }
    }
}
