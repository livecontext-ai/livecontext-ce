package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderWithEpochRow;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for {@link AgentWorkflowFireService#buildResult} and its sub-methods:
 * <ul>
 *   <li>resolveStatus - COMPLETED, FAILED, AWAITING_INPUT, RUNNING</li>
 *   <li>addBlockingSignalInfo - signal node + type populated</li>
 *   <li>buildAllNodeStatuses - outputs, errors, skipped, awaiting_signal, running</li>
 *   <li>terminal node computation - non-trigger nodes with no outgoing edges</li>
 *   <li>duration extraction - read from stateSnapshot totalDurationMs</li>
 *   <li>output enrichment - actual data from step output storage</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWorkflowFireService - buildResult")
class AgentWorkflowFireServiceBuildResultTest {

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowExecutionService executionService;
    @Mock ReusableTriggerService reusableTriggerService;
    @Mock SignalWaitRepository signalWaitRepository;
    @Mock EditorRunResolver editorRunResolver;
    @Mock StepOutputService stepOutputService;
    @Mock WorkflowStepDataRepository stepDataRepository;
    @Mock WorkflowEpochService epochService;

    private AgentWorkflowFireService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String RUN_ID = "run-build-result-test";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowFireService(
                runRepository, executionService, reusableTriggerService,
                signalWaitRepository, editorRunResolver, mapper,
                stepOutputService, stepDataRepository, epochService);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private WorkflowRunEntity runWith(RunStatus status) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID);
        lenient().when(run.getStatus()).thenReturn(status);
        lenient().when(run.getPlanVersion()).thenReturn(1);
        lenient().when(run.getMetadata()).thenReturn(Map.of());
        lenient().when(run.getStateSnapshot()).thenReturn(null);
        return run;
    }

    private WorkflowEntity workflow(Integer pinnedVersion) {
        WorkflowEntity e = new WorkflowEntity();
        e.setId(UUID.randomUUID());
        e.setPinnedVersion(pinnedVersion);
        e.setName("Test");
        return e;
    }

    private WorkflowPlan emptyPlan() {
        return WorkflowPlan.fromMap(Map.of(
                "triggers", List.of(Map.of("id", "start", "label", "Start", "type", "manual")),
                "mcps", List.of(), "cores", List.of(), "edges", List.of()));
    }

    private TriggerExecutionResult successResult(int epoch) {
        return TriggerExecutionResult.success(RUN_ID, "trigger:start", TriggerType.MANUAL, Set.of(), epoch);
    }

    private TriggerExecutionResult failureResult() {
        return TriggerExecutionResult.failure(RUN_ID, "trigger:start", TriggerType.MANUAL, "Execution failed");
    }

    /**
     * Build a linear plan: trigger:start → mcp:step → mcp:final
     * Terminal node = mcp:final (no outgoing edge)
     */
    private WorkflowPlan linearPlan() {
        Map<String, Object> trigger = Map.of("id", "start", "label", "Start", "type", "manual");
        Map<String, Object> step = Map.of("id", "step", "label", "Step", "type", "mcp");
        Map<String, Object> finalStep = Map.of("id", "final", "label", "Final", "type", "mcp");
        Map<String, Object> edge1 = Map.of("from", "trigger:start", "to", "mcp:step");
        Map<String, Object> edge2 = Map.of("from", "mcp:step", "to", "mcp:final");
        return WorkflowPlan.fromMap(Map.of(
                "triggers", List.of(trigger),
                "mcps", List.of(step, finalStep),
                "cores", List.of(),
                "edges", List.of(edge1, edge2)));
    }

    /** Snapshot JSON where mcp:step=COMPLETED, mcp:final in given status set. */
    private String snapshotWith(String finalNodeSet) throws Exception {
        Map<String, Object> epoch = new LinkedHashMap<>();
        epoch.put("completedNodeIds", "completedNodeIds".equals(finalNodeSet)
                ? List.of("mcp:step", "mcp:final") : List.of("mcp:step"));
        epoch.put("failedNodeIds", "failedNodeIds".equals(finalNodeSet)
                ? List.of("mcp:final") : List.of());
        epoch.put("skippedNodeIds", "skippedNodeIds".equals(finalNodeSet)
                ? List.of("mcp:final") : List.of());
        epoch.put("runningNodeIds", "runningNodeIds".equals(finalNodeSet)
                ? List.of("mcp:final") : List.of());
        epoch.put("readyNodeIds", List.of());
        epoch.put("awaitingSignalNodeIds", "awaitingSignalNodeIds".equals(finalNodeSet)
                ? List.of("mcp:final") : List.of());

        return mapper.writeValueAsString(Map.of(
                "version", 3,
                "dags", Map.of("trigger:start", Map.of(
                        "currentEpoch", 0, "currentSpawn", 0, "fireCount", 1,
                        "epochs", Map.of("0", epoch),
                        "activeEpochs", List.of()
                ))
        ));
    }

    /** Full chain snapshot: step1 FAILED → step2 SKIPPED → final SKIPPED. */
    private String failureChainSnapshot() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "version", 3,
                "dags", Map.of("trigger:start", Map.of(
                        "currentEpoch", 0, "currentSpawn", 0, "fireCount", 1,
                        "epochs", Map.of("0", Map.of(
                                "completedNodeIds", List.of(),
                                "failedNodeIds", List.of("mcp:step"),
                                "skippedNodeIds", List.of("mcp:final"),
                                "runningNodeIds", List.of(),
                                "readyNodeIds", List.of(),
                                "awaitingSignalNodeIds", List.of()
                        )),
                        "activeEpochs", List.of()
                ))
        ));
    }

    private WorkflowStepDataEntity stepEntity(int epoch, UUID outputStorageId, String errorMessage) {
        WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
        lenient().when(entity.getEpoch()).thenReturn(epoch);
        lenient().when(entity.getOutputStorageId()).thenReturn(outputStorageId);
        lenient().when(entity.getErrorMessage()).thenReturn(errorMessage);
        return entity;
    }

    // ── status field ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("status resolution")
    class StatusTests {

        @Test
        @DisplayName("TriggerResult failure → status=FAILED regardless of run status")
        void triggerFailure_statusFailed() {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, failureResult(), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("Run WAITING_TRIGGER (cycle done), no metadata → status=COMPLETED")
        void waitingTrigger_noMeta_completed() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Run WAITING_TRIGGER with lastCycleResult=PARTIAL_SUCCESS → status from metadata")
        void waitingTrigger_partialSuccess_fromMetadata() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getMetadata()).thenReturn(Map.of("lastCycleResult", "partial_success"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(1), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("PARTIAL_SUCCESS");
        }

        @Test
        @DisplayName("Run COMPLETED → status=COMPLETED")
        void runCompleted_statusCompleted() {
            WorkflowRunEntity run = runWith(RunStatus.COMPLETED);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Run RUNNING with blocking signals → status=AWAITING_INPUT")
        void running_withBlockingSignals_awaitingInput() {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(2L);
            when(signalWaitRepository.findByRunId(RUN_ID)).thenReturn(List.of());

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("AWAITING_INPUT");
        }

        @Test
        @DisplayName("Run RUNNING with no blocking signals → status=RUNNING")
        void running_noBlockingSignals_running() {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(0L);

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("Run FAILED terminal status → status=FAILED (passthrough)")
        void runFailed_terminal() {
            WorkflowRunEntity run = runWith(RunStatus.FAILED);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("FAILED");
        }
    }

    // ── core fields ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("core result fields")
    class CoreFieldTests {

        @Test
        @DisplayName("run_id, trigger_id, epoch, fire_count, plan_version populated")
        void coreFields_populated() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getPlanVersion()).thenReturn(3);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            TriggerExecutionResult tr = TriggerExecutionResult.success(
                    RUN_ID, "trigger:start", TriggerType.MANUAL, Set.of(), 2);
            Map<String, Object> result = service.buildResult(run, tr, workflow(3), emptyPlan());

            assertThat(result.get("run_id")).isEqualTo(RUN_ID);
            assertThat(result.get("trigger_id")).isEqualTo("trigger:start");
            assertThat(result.get("epoch")).isEqualTo(2);
            assertThat(result.get("fire_count")).isEqualTo(3);   // epoch + 1
            assertThat(result.get("plan_version")).isEqualTo(3);
            assertThat(result.get("pinned_version")).isEqualTo(3);
        }

        @Test
        @DisplayName("pinned_version is null when workflow has no pin")
        void pinnedVersion_null_whenNotPinned() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("pinned_version")).isNull();
        }

        @Test
        @DisplayName("run re-fetched from repository to get post-execution state")
        void run_refetched_afterExecution() {
            WorkflowRunEntity originalRun = runWith(RunStatus.RUNNING);
            WorkflowRunEntity refetchedRun = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(refetchedRun));

            Map<String, Object> result = service.buildResult(originalRun, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("original run used when re-fetch returns empty")
        void run_originalUsed_whenRefetchEmpty() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
        }
    }

    // ── duration ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("duration extraction")
    class DurationTests {

        @Test
        @DisplayName("duration_ms extracted from stateSnapshot.totalDurationMs")
        void duration_extractedFromSnapshot() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            String snapshot = mapper.writeValueAsString(Map.of("totalDurationMs", 3500));
            when(run.getStateSnapshot()).thenReturn(snapshot);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("duration_ms")).isEqualTo(3500L);
        }

        @Test
        @DisplayName("duration_ms absent when stateSnapshot is null")
        void duration_absent_whenSnapshotNull() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result).doesNotContainKey("duration_ms");
        }

        @Test
        @DisplayName("duration_ms absent when stateSnapshot has no totalDurationMs key")
        void duration_absent_whenKeyMissing() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            String snapshot = mapper.writeValueAsString(Map.of("version", 3));
            when(run.getStateSnapshot()).thenReturn(snapshot);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result).doesNotContainKey("duration_ms");
        }
    }

    // ── blocking signal info ───────────────────────────────────────────────

    @Nested
    @DisplayName("blocking signal info")
    class BlockingSignalTests {

        @Test
        @DisplayName("blocking_on populated with node and signal_type when AWAITING_INPUT")
        void blockingOn_populated() {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(1L);

            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.isBlocking()).thenReturn(true);
            when(signal.isActive()).thenReturn(true);
            when(signal.getNodeId()).thenReturn("core:approval_step");
            when(signal.getSignalType()).thenReturn(
                    com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            when(signalWaitRepository.findByRunId(RUN_ID)).thenReturn(List.of(signal));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("AWAITING_INPUT");
            @SuppressWarnings("unchecked")
            var blockingOn = (Map<String, Object>) result.get("blocking_on");
            assertThat(blockingOn).isNotNull();
            assertThat(blockingOn.get("node")).isEqualTo("core:approval_step");
            assertThat(blockingOn.get("signal_type")).isEqualTo("USER_APPROVAL");
        }

        @Test
        @DisplayName("blocking_on absent when no blocking signals")
        void blockingOn_absent_whenNoSignals() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result).doesNotContainKey("blocking_on");
        }
    }

    // ── all node statuses (outputs, errors, skipped, running, awaiting) ───

    @Nested
    @DisplayName("node status report")
    class NodeStatusReportTests {

        @Test
        @DisplayName("No snapshot → no outputs/errors/skipped keys")
        void noSnapshot_noKeys() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            // No snapshot → no nodes have any status → no keys added
            assertThat(result).doesNotContainKey("outputs");
            assertThat(result).doesNotContainKey("errors");
            assertThat(result).doesNotContainKey("skipped");
        }

        @Test
        @DisplayName("Empty plan (no non-trigger nodes) → no outputs key")
        void emptyPlan_noOutputs() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result).doesNotContainKey("outputs");
        }

        @Test
        @DisplayName("Completed terminal node appears in outputs")
        void completedTerminal_inOutputs() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var outputs = (List<Map<String, Object>>) result.get("outputs");
            assertThat(outputs).hasSize(1);
            assertThat(outputs.get(0).get("node_id")).isEqualTo("mcp:final");
            assertThat(outputs.get(0).get("status")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("step1 FAILED + final SKIPPED → errors + skipped both populated")
        void failureChain_errorsAndSkipped() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getMetadata()).thenReturn(Map.of("lastCycleResult", "failed"));
            when(run.getStateSnapshot()).thenReturn(failureChainSnapshot());
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            // Mock error message for the failed node
            WorkflowStepDataEntity failedStep = stepEntity(0, null, "API returned 500");
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(failedStep));

            Map<String, Object> result = service.buildResult(
                    run, successResult(0), workflow(null), linearPlan(), TENANT_ID);

            assertThat(result.get("status")).isEqualTo("FAILED");

            // errors contains the failed node with its message
            @SuppressWarnings("unchecked")
            var errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).get("node")).isEqualTo("mcp:step");
            assertThat(errors.get(0).get("error")).isEqualTo("API returned 500");

            // skipped contains the downstream node
            @SuppressWarnings("unchecked")
            var skipped = (List<String>) result.get("skipped");
            assertThat(skipped).containsExactly("mcp:final");

            // no outputs (nothing completed that's terminal)
            assertThat(result).doesNotContainKey("outputs");
        }

        @Test
        @DisplayName("running node appears in 'running' list")
        void runningNode_inRunningList() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("runningNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(0L);

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var runningNodes = (List<String>) result.get("running");
            assertThat(runningNodes).contains("mcp:final");
            assertThat(result).doesNotContainKey("outputs");
        }

        @Test
        @DisplayName("P2.2 site 8 - Redis running overlay merges with JSONB running into 'running' list (post-elision authoritative)")
        void redisOverlay_mergedIntoRunningList() throws Exception {
            // Pre-P2.3 elision: JSONB carries running, Redis is additive.
            // Post-elision: JSONB running is empty, Redis is the only source.
            // This test pins the union-merge contract for the JSONB-empty case
            // (i.e. the post-P2.3 load-bearing behavior).
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            // JSONB shows step+final completed (no JSONB running entry)
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(0L);

            // Wire a Redis overlay that flags mcp:step as running. Field-injected via
            // @Autowired(required=false), so reflection mirrors the runtime path.
            com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker tracker =
                    mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
            when(tracker.getRunningCountsAcrossEpochs(RUN_ID))
                    .thenReturn(Map.of("mcp:step", 1));
            org.springframework.test.util.ReflectionTestUtils.setField(service, "runningNodeTracker", tracker);

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var runningNodes = (List<String>) result.get("running");
            assertThat(runningNodes).contains("mcp:step");
        }

        @Test
        @DisplayName("P2.2 site 8 - RunningNodeTracker bean absent → JSONB-only fallback (fail-OPEN)")
        void redisOverlay_absentTracker_failsOpenToJsonb() throws Exception {
            // Negative control: with the optional tracker null, the JSONB running set
            // alone drives the result - agent-acceptable degradation per design row 8.
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("runningNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(0L);
            // Explicitly clear any prior wiring from sibling tests in the class.
            org.springframework.test.util.ReflectionTestUtils.setField(service, "runningNodeTracker", null);

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var runningNodes = (List<String>) result.get("running");
            assertThat(runningNodes).contains("mcp:final");
        }

        @Test
        @DisplayName("awaiting_signal node appears in 'awaiting_signal' list")
        void awaitingSignalNode_inAwaitingList() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("awaitingSignalNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            when(signalWaitRepository.countActiveBlockingByRunId(RUN_ID)).thenReturn(1L);
            when(signalWaitRepository.findByRunId(RUN_ID)).thenReturn(List.of());

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var awaiting = (List<String>) result.get("awaiting_signal");
            assertThat(awaiting).contains("mcp:final");
        }

        @Test
        @DisplayName("Node with port edge - port stripped for terminal detection")
        void portSuffix_strippedForTerminalDetection() {
            Map<String, Object> trigger = Map.of("id", "start", "label", "Start", "type", "manual");
            Map<String, Object> action = Map.of("id", "action", "label", "Action", "type", "mcp");
            Map<String, Object> edgeTriggerToCheck = Map.of("from", "trigger:start", "to", "core:check");
            Map<String, Object> edgeCheckToAction = Map.of("from", "core:check:if", "to", "mcp:action");
            Map<String, Object> core = Map.of("id", "check", "label", "Check",
                    "type", "decision", "condition", "true");

            WorkflowPlan plan = WorkflowPlan.fromMap(Map.of(
                    "triggers", List.of(trigger),
                    "mcps", List.of(action),
                    "cores", List.of(core),
                    "edges", List.of(edgeTriggerToCheck, edgeCheckToAction)));

            // core:check completed, mcp:action completed (terminal)
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            try {
                String snapshot = mapper.writeValueAsString(Map.of(
                        "version", 3,
                        "dags", Map.of("trigger:start", Map.of(
                                "currentEpoch", 0, "currentSpawn", 0, "fireCount", 1,
                                "epochs", Map.of("0", Map.of(
                                        "completedNodeIds", List.of("core:check", "mcp:action"),
                                        "failedNodeIds", List.of(),
                                        "skippedNodeIds", List.of(),
                                        "runningNodeIds", List.of(),
                                        "readyNodeIds", List.of(),
                                        "awaitingSignalNodeIds", List.of()
                                )),
                                "activeEpochs", List.of()
                        ))
                ));
                when(run.getStateSnapshot()).thenReturn(snapshot);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), plan);

            @SuppressWarnings("unchecked")
            var outputs = (List<Map<String, Object>>) result.get("outputs");
            // Only mcp:action is terminal (core:check has outgoing edge)
            List<String> outputIds = outputs.stream()
                    .map(n -> (String) n.get("node_id"))
                    .toList();
            assertThat(outputIds).contains("mcp:action");
            assertThat(outputIds).doesNotContain("core:check");
        }
    }

    // ── output enrichment ─────────────────────────────────────────────────

    @Nested
    @DisplayName("output enrichment")
    class OutputEnrichmentTests {

        @Test
        @DisplayName("outputs enriched with actual data from step output storage")
        void outputs_enrichedWithActualData() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:final"))
                    .thenReturn(List.of(step));
            Map<String, Object> outputData = Map.of("name", "John", "age", 30);
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID)).thenReturn(outputData);

            Map<String, Object> result = service.buildResult(
                    run, successResult(0), workflow(null), linearPlan(), TENANT_ID);

            @SuppressWarnings("unchecked")
            var outputs = (List<Map<String, Object>>) result.get("outputs");
            assertThat(outputs).hasSize(1);
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) outputs.get(0).get("output");
            assertThat(output).containsEntry("name", "John");
            assertThat(output).containsEntry("age", 30);
        }

        @Test
        @DisplayName("outputs truncate large arrays with row_count/preview/truncated")
        void outputs_truncatesLargeArrays() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:final"))
                    .thenReturn(List.of(step));

            List<Map<String, Object>> bigList = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                bigList.add(Map.of("id", i, "name", "item-" + i));
            }
            Map<String, Object> outputData = new LinkedHashMap<>();
            outputData.put("items", bigList);
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID)).thenReturn(outputData);

            Map<String, Object> result = service.buildResult(
                    run, successResult(0), workflow(null), linearPlan(), TENANT_ID);

            @SuppressWarnings("unchecked")
            var outputs = (List<Map<String, Object>>) result.get("outputs");
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) outputs.get(0).get("output");
            @SuppressWarnings("unchecked")
            var truncatedItems = (Map<String, Object>) output.get("items");
            assertThat(truncatedItems.get("row_count")).isEqualTo(10);
            assertThat(truncatedItems.get("truncated")).isEqualTo(true);
            assertThat((List<?>) truncatedItems.get("preview")).hasSize(3);
        }

        @Test
        @DisplayName("no enrichment when tenantId is null (backward compat)")
        void noEnrichment_whenTenantIdNull() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(
                    run, successResult(0), workflow(null), linearPlan());

            @SuppressWarnings("unchecked")
            var outputs = (List<Map<String, Object>>) result.get("outputs");
            assertThat(outputs).hasSize(1);
            assertThat(outputs.get(0)).doesNotContainKey("output");
            verifyNoInteractions(stepDataRepository);
        }
    }

    // ── error info ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error info")
    class ErrorInfoTests {

        @Test
        @DisplayName("Trigger failure message stored in 'error' key")
        void triggerFailure_errorMessageStored() {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            TriggerExecutionResult tr = TriggerExecutionResult.failure(
                    RUN_ID, "trigger:start", TriggerType.MANUAL, "Database connection timeout");

            Map<String, Object> result = service.buildResult(run, tr, workflow(null), emptyPlan());

            assertThat(result.get("status")).isEqualTo("FAILED");
            assertThat(result.get("error")).isEqualTo("Database connection timeout");
        }

        @Test
        @DisplayName("No error key on successful completion")
        void success_noErrorKey() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            Map<String, Object> result = service.buildResult(run, successResult(0), workflow(null), emptyPlan());

            assertThat(result).doesNotContainKey("error");
            assertThat(result).doesNotContainKey("errors");
        }

        @Test
        @DisplayName("errors field contains {node, error} for failed nodes")
        void errors_includeNodeMessages() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(run.getMetadata()).thenReturn(Map.of("lastCycleResult", "failed"));
            when(run.getStateSnapshot()).thenReturn(failureChainSnapshot());
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            WorkflowStepDataEntity stepWithError = stepEntity(0, null, "Connection refused");
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(stepWithError));

            Map<String, Object> result = service.buildResult(
                    run, successResult(0), workflow(null), linearPlan(), TENANT_ID);

            @SuppressWarnings("unchecked")
            var errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).get("node")).isEqualTo("mcp:step");
            assertThat(errors.get(0).get("error")).isEqualTo("Connection refused");
        }
    }

    // ── macro report (Phase 1 - no epoch param) ──────────────────────────

    @Nested
    @DisplayName("macro report (buildRunMacroReport)")
    class MacroReportTests {

        @Test
        @DisplayName("macro returns run-level info with NEXT hint")
        void macro_returnsRunLevelInfo() {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            when(epochService.listEpochHeaders(RUN_ID)).thenReturn(List.of());

            Map<String, Object> result = service.buildRunMacroReport(run, emptyPlan(), TENANT_ID);

            assertThat(result.get("run_id")).isEqualTo(RUN_ID);
            assertThat(result.get("status")).isEqualTo("WAITING_TRIGGER");
            assertThat(result.get("total_epochs")).isEqualTo(0);
            assertThat((String) result.get("NEXT")).contains("epoch=N");
        }

        @Test
        @DisplayName("macro includes dags summary from snapshot")
        void macro_includesDagsSummary() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            String snapshot = mapper.writeValueAsString(Map.of(
                    "version", 3,
                    "dags", Map.of("trigger:start", Map.of(
                            "currentEpoch", 2, "currentSpawn", 0, "fireCount", 3,
                            "epochs", Map.of(), "activeEpochs", List.of()
                    ))
            ));
            when(run.getStateSnapshot()).thenReturn(snapshot);
            when(epochService.listEpochHeaders(RUN_ID)).thenReturn(List.of());

            Map<String, Object> result = service.buildRunMacroReport(run, emptyPlan(), TENANT_ID);

            @SuppressWarnings("unchecked")
            var dags = (Map<String, Object>) result.get("dags");
            assertThat(dags).containsKey("trigger:start");
            @SuppressWarnings("unchecked")
            var dagInfo = (Map<String, Object>) dags.get("trigger:start");
            assertThat(dagInfo.get("current_epoch")).isEqualTo(2);
            assertThat(dagInfo.get("fire_count")).isEqualTo(3);
        }

        @Test
        @DisplayName("macro includes epochs from persistent data")
        void macro_includesEpochsFromPersistentData() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            // Build epoch state JSON with 2 completed and 1 failed node
            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header0 = new EpochHeaderWithEpochRow(
                    0, epochStateJson, false,
                    java.time.Instant.parse("2026-03-20T10:00:00Z"),
                    java.time.Instant.parse("2026-03-20T10:00:05Z"),
                    "trigger:start", 5000L);
            when(epochService.listEpochHeaders(RUN_ID)).thenReturn(List.of(header0));

            Map<String, Object> result = service.buildRunMacroReport(run, emptyPlan(), TENANT_ID);

            assertThat(result.get("total_epochs")).isEqualTo(1);
            @SuppressWarnings("unchecked")
            var epochs = (List<Map<String, Object>>) result.get("epochs");
            assertThat(epochs).hasSize(1);
            assertThat(epochs.get(0).get("epoch")).isEqualTo(0);
            assertThat(epochs.get(0).get("trigger_id")).isEqualTo("trigger:start");
            assertThat(epochs.get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(epochs.get(0).get("duration_ms")).isEqualTo(5000L);
            @SuppressWarnings("unchecked")
            var counts = (Map<String, Object>) epochs.get(0).get("node_counts");
            assertThat(counts.get("completed")).isEqualTo(2);
            assertThat(counts.get("failed")).isEqualTo(0);
        }

        @Test
        @DisplayName("macro returns empty epochs when no headers")
        void macro_emptyEpochsWhenNoHeaders() {
            WorkflowRunEntity run = runWith(RunStatus.COMPLETED);
            when(epochService.listEpochHeaders(RUN_ID)).thenReturn(List.of());

            Map<String, Object> result = service.buildRunMacroReport(run, emptyPlan(), TENANT_ID);

            @SuppressWarnings("unchecked")
            var epochs = (List<Map<String, Object>>) result.get("epochs");
            assertThat(epochs).isEmpty();
            assertThat(result.get("total_epochs")).isEqualTo(0);
        }
    }

    // ── epoch detail report (Phase 2 - epoch=N) ─────────────────────────

    @Nested
    @DisplayName("epoch detail report (buildEpochDetailReport)")
    class EpochDetailTests {

        @Test
        @DisplayName("detail loads from persistent header - shows all completed nodes with label/type/status")
        void detail_loadsFromPersistentHeader() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 3000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            assertThat(result.get("run_id")).isEqualTo(RUN_ID);
            assertThat(result.get("epoch")).isEqualTo(0);
            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            // Both mcp:step and mcp:final are completed → both listed
            assertThat(nodes).hasSize(2);
            assertThat(nodes).extracting(n -> n.get("node_id"))
                    .containsExactlyInAnyOrder("mcp:step", "mcp:final");
            // Each node has label, type, status - but no output (output requires get_node_output)
            var finalNode = nodes.stream().filter(n -> "mcp:final".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(finalNode.get("label")).isEqualTo("Final");
            assertThat(finalNode.get("type")).isEqualTo("mcp");
            assertThat(finalNode.get("status")).isEqualTo("COMPLETED");
            assertThat(finalNode).doesNotContainKey("output");
        }

        @Test
        @DisplayName("detail falls back to live snapshot when header is null")
        void detail_fallbackToLiveSnapshot() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(null);
            when(run.getStateSnapshot()).thenReturn(snapshotWith("completedNodeIds"));

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            assertThat(result).doesNotContainKey("error");
            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            // Both mcp:step and mcp:final are completed in the snapshot
            assertThat(nodes).hasSize(2);
            assertThat(nodes).extracting(n -> n.get("node_id"))
                    .containsExactlyInAnyOrder("mcp:step", "mcp:final");
            assertThat(nodes).allSatisfy(n -> assertThat(n.get("status")).isEqualTo("COMPLETED"));
        }

        @Test
        @DisplayName("detail returns error when epoch not found in either source")
        void detail_epochNotFound_returnsError() {
            WorkflowRunEntity run = runWith(RunStatus.COMPLETED);
            when(epochService.getEpochHeader(RUN_ID, 99)).thenReturn(null);
            // No snapshot data either

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 99, TENANT_ID);

            assertThat(result.get("error")).asString().contains("Epoch 99 not found");
            assertThat(result.get("hint")).asString().contains("without epoch");
        }

        @Test
        @DisplayName("detail shows failed nodes with inline error message")
        void detail_failedNodes_includeErrorMessage() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of("mcp:step"),
                    "skippedNodeIds", List.of("mcp:final"),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            WorkflowStepDataEntity stepData = stepEntity(0, null, "API returned 500");
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(stepData));

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).hasSize(2); // mcp:step (FAILED) + mcp:final (SKIPPED)
            var failedNode = nodes.stream().filter(n -> "mcp:step".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(failedNode.get("status")).isEqualTo("FAILED");
            assertThat(failedNode.get("error")).isEqualTo("API returned 500");
            var skippedNode = nodes.stream().filter(n -> "mcp:final".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(skippedNode.get("status")).isEqualTo("SKIPPED");
        }

        @Test
        @DisplayName("PENDING nodes are filtered out from epoch detail")
        void detail_pendingNodesFiltered() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);

            // Only mcp:step completed, mcp:final still pending (not in any set)
            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, true,
                    java.time.Instant.now(), null, "trigger:start", null);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).get("node_id")).isEqualTo("mcp:step");
            assertThat(nodes.get(0).get("status")).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("RUNNING and AWAITING_SIGNAL nodes shown in epoch detail")
        void detail_runningAndAwaitingNodes() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of("mcp:final"),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, true,
                    java.time.Instant.now(), null, "trigger:start", null);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).hasSize(2);
            var runningNode = nodes.stream().filter(n -> "mcp:final".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(runningNode.get("status")).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("epoch detail with empty plan returns empty nodes list")
        void detail_emptyPlan_emptyNodes() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 500L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, emptyPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).isEmpty();
        }

        @Test
        @DisplayName("epoch detail with mixed node types (agent, core, table, interface)")
        void detail_mixedNodeTypes() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            // Plan with agent, core, and mcp nodes
            Map<String, Object> trigger = Map.of("id", "start", "label", "Start", "type", "manual");
            Map<String, Object> mcpStep = Map.of("id", "api-call", "label", "API Call", "type", "mcp");
            Map<String, Object> agentStep = Map.of("id", "analyzer", "label", "Analyzer", "type", "agent");
            Map<String, Object> coreStep = Map.of("id", "check", "label", "Check", "type", "decision", "condition", "true");
            Map<String, Object> edge1 = Map.of("from", "trigger:start", "to", "mcp:api_call");
            Map<String, Object> edge2 = Map.of("from", "mcp:api_call", "to", "agent:analyzer");
            Map<String, Object> edge3 = Map.of("from", "agent:analyzer", "to", "core:check");
            WorkflowPlan mixedPlan = WorkflowPlan.fromMap(Map.of(
                    "triggers", List.of(trigger),
                    "mcps", List.of(mcpStep),
                    "agents", List.of(agentStep),
                    "cores", List.of(coreStep),
                    "edges", List.of(edge1, edge2, edge3)));

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:api_call", "agent:analyzer", "core:check"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 2000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, mixedPlan, 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).hasSize(3);

            var mcpNode = nodes.stream().filter(n -> "mcp:api_call".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(mcpNode.get("label")).isEqualTo("API Call");
            assertThat(mcpNode.get("type")).isEqualTo("mcp");

            var agentNode = nodes.stream().filter(n -> "agent:analyzer".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(agentNode.get("label")).isEqualTo("Analyzer");
            assertThat(agentNode.get("type")).isEqualTo("agent");

            var coreNode = nodes.stream().filter(n -> "core:check".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(coreNode.get("label")).isEqualTo("Check");
            assertThat(coreNode.get("type")).isEqualTo("decision");
        }

        @Test
        @DisplayName("epoch detail NEXT hint includes correct run_id and epoch")
        void detail_nextHintContainsCorrectIds() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 500L);
            when(epochService.getEpochHeader(RUN_ID, 3)).thenReturn(header);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 3, TENANT_ID);

            String next = (String) result.get("NEXT");
            assertThat(next).contains("get_node_output");
            assertThat(next).contains(RUN_ID);
            assertThat(next).contains("epoch=3");
        }
    }

    // ── node output report (Phase 3 - get_node_output) ──────────────────

    @Nested
    @DisplayName("node output report (buildNodeOutputReport)")
    class NodeOutputReportTests {

        @Test
        @DisplayName("regression - large STRING in node output is byte-capped (was leaking inline base64 from image_generation pre-v2.0)")
        void nodeOutput_largeStringByteCapped() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));
            // 256 KB of base64-shaped chars - exceeds the 128 KB cap and
            // would blow tool-result token budget if shipped inline (this is
            // exactly what image_generation leaked pre-v1.9 and what
            // workflow(get_node_output) leaked pre-v2.0).
            String fakeB64 = "A".repeat(256 * 1024);
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of(
                        "candidates", List.of(Map.of(
                            "content", Map.of(
                                "parts", List.of(
                                    Map.of("inlineData", Map.of("data", fakeB64))
                                ))))
                    ));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.get("output");
            // Walk to the deeply-nested string; assert it was replaced by a stub.
            @SuppressWarnings("unchecked")
            var candidates = (List<Map<String, Object>>) output.get("candidates");
            @SuppressWarnings("unchecked")
            var parts = (List<Map<String, Object>>) ((Map<String, Object>) candidates.get(0).get("content")).get("parts");
            Object data = ((Map<String, Object>) parts.get(0).get("inlineData")).get("data");
            assertThat(data)
                    .as("string >32 KB must be byte-capped - agents pulling node output should never see multi-MB strings")
                    .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> truncated = (Map<String, Object>) data;
            assertThat(truncated)
                    .containsEntry("truncated", true)
                    .containsKey("preview")
                    .containsKey("note"); // base64-shaped → "note" hint is added
        }

        @Test
        @DisplayName("FileRef Map in node output is preserved verbatim (lightweight reference, no walk inside)")
        void nodeOutput_fileRefPreserved() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));

            Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "tenant1/general/catalog-binary/img.png",
                "name", "img.png",
                "mimeType", "image/png",
                "size", 1_500_000L
            );
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of("attachment", fileRef));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.get("output");
            assertThat(output.get("attachment")).isEqualTo(fileRef);
        }

        @Test
        @DisplayName("returns full output for a completed node - no truncation")
        void nodeOutput_returnsFullOutput() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of("result", "success", "data", List.of(1, 2, 3, 4, 5)));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result.get("run_id")).isEqualTo(RUN_ID);
            assertThat(result.get("node_id")).isEqualTo("mcp:step");
            assertThat(result.get("label")).isEqualTo("Step");
            assertThat(result.get("type")).isEqualTo("mcp");
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.get("output");
            assertThat(output).containsEntry("result", "success");
            // Full output - no truncation (unlike buildResult which truncates lists ≥4)
            @SuppressWarnings("unchecked")
            var data = (List<Integer>) output.get("data");
            assertThat(data).hasSize(5);
        }

        @Test
        @DisplayName("large lists are NOT truncated in node output (unlike epoch summary)")
        void nodeOutput_noTruncation_largeLists() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:final"))
                    .thenReturn(List.of(step));

            List<Map<String, Object>> bigList = new ArrayList<>();
            for (int i = 0; i < 50; i++) bigList.add(Map.of("id", i, "name", "item-" + i));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of("items", bigList));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:final", TENANT_ID);

            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.get("output");
            // Full list - 50 items, NOT truncated to 3
            @SuppressWarnings("unchecked")
            var items = (List<?>) output.get("items");
            assertThat(items).hasSize(50);
        }

        @Test
        @DisplayName("returns error details for a failed node")
        void nodeOutput_failedNode_returnsError() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of("mcp:step"),
                    "skippedNodeIds", List.of("mcp:final"),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            WorkflowStepDataEntity stepData = stepEntity(0, null, "Connection timeout after 30s");
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(stepData));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result.get("status")).isEqualTo("FAILED");
            // The zoom-mode path now surfaces the row's errorMessage in `error`
            // (consistent with the per-item error field used in list mode).
            assertThat(result.get("error")).isEqualTo("Connection timeout after 30s");
        }

        @Test
        @DisplayName("returns SKIPPED status for skipped node")
        void nodeOutput_skippedNode() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of("mcp:step"),
                    "skippedNodeIds", List.of("mcp:final"),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:final", TENANT_ID);

            assertThat(result.get("status")).isEqualTo("SKIPPED");
            assertThat(result.get("label")).isEqualTo("Final");
        }

        @Test
        @DisplayName("returns AWAITING_SIGNAL status")
        void nodeOutput_awaitingSignal() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.RUNNING);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of("mcp:final")
            ));
            var header = new EpochHeaderRow(epochStateJson, true,
                    java.time.Instant.now(), null, "trigger:start", null);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:final", TENANT_ID);

            assertThat(result.get("status")).isEqualTo("AWAITING_SIGNAL");
        }

        @Test
        @DisplayName("returns error when epoch not found")
        void nodeOutput_epochNotFound() {
            WorkflowRunEntity run = runWith(RunStatus.COMPLETED);
            when(epochService.getEpochHeader(RUN_ID, 99)).thenReturn(null);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 99, "mcp:step", TENANT_ID);

            assertThat(result.get("error")).asString().contains("Epoch 99 not found");
            // Should still have the basic fields
            assertThat(result.get("run_id")).isEqualTo(RUN_ID);
            assertThat(result.get("node_id")).isEqualTo("mcp:step");
        }

        @Test
        @DisplayName("handles unknown node_id gracefully - no label/type but still works")
        void nodeOutput_unknownNodeId() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:unknown_node"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 500L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:unknown_node", TENANT_ID);

            assertThat(result.get("node_id")).isEqualTo("mcp:unknown_node");
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            // No label/type since it's not in the plan
            assertThat(result).doesNotContainKey("label");
            assertThat(result).doesNotContainKey("type");
        }

        @Test
        @DisplayName("includes timing info from step data")
        void nodeOutput_includesTimingInfo() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            java.time.Instant start = java.time.Instant.parse("2026-04-01T10:00:00Z");
            java.time.Instant end = java.time.Instant.parse("2026-04-01T10:00:02.500Z");

            UUID storageId = UUID.randomUUID();
            WorkflowStepDataEntity step = stepEntity(0, storageId, null);
            when(step.getStartTime()).thenReturn(start);
            when(step.getEndTime()).thenReturn(end);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of("result", "ok"));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result.get("started_at")).isEqualTo(start.toString());
            assertThat(result.get("ended_at")).isEqualTo(end.toString());
            assertThat(result.get("duration_ms")).isEqualTo(2500L);
        }

        @Test
        @DisplayName("returns note when no step data found for node in epoch")
        void nodeOutput_noStepData_returnsNote() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // Step data exists but for a different epoch
            WorkflowStepDataEntity step = stepEntity(5, UUID.randomUUID(), null);
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("output")).isNull();
            assertThat(result.get("note")).asString().contains("No step data found");
        }

        @Test
        @DisplayName("null tenantId skips output loading")
        void nodeOutput_nullTenantId_skipsOutput() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step", "mcp:final"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", null);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            // Output blob loading is the expensive part, and is what the
            // tenantId guard is supposed to prevent. Per-row metadata queries
            // are harmless - the new split-aware path queries them so it can
            // surface execution_count / status_counts even with no tenant.
            verifyNoInteractions(stepOutputService);
        }

        @Test
        @DisplayName("NEXT hint points back to epoch overview")
        void nodeOutput_nextHintPointsBack() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 500L);
            when(epochService.getEpochHeader(RUN_ID, 2)).thenReturn(header);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 2, "mcp:step", TENANT_ID);

            String next = (String) result.get("NEXT");
            assertThat(next).contains("get_run");
            assertThat(next).contains(RUN_ID);
            assertThat(next).contains("epoch=2");
        }
    }

    // ── split-aware get_node_output / get_run epoch detail ──────────────────
    //
    // These tests guard the bug behind this whole feature: prior to this
    // change, when a node had N persisted rows for the same (runId, nodeId,
    // epoch) - the normal case for any node downstream of a split, inside a
    // loop, or re-run via spawn - the agent saw exactly one arbitrary row
    // (findFirst() with no tie-breaker) plus a node-level status that hid the
    // mixed item statuses. The frontend's useStepData / useAggregatedSteps
    // hooks already surfaced the breakdown to users; the agent did not.

    @Nested
    @DisplayName("split-aware node inspection")
    class SplitAwareNodeInspectionTests {

        @Test
        @DisplayName("epoch detail - split node with 3 rows surfaces execution_count + status_counts")
        void epochDetail_multiItemNode_addsCounts() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // 3 items: 2 COMPLETED, 1 SKIPPED - node-level status is COMPLETED
            // (the node finished its fan-out work) but the breakdown matters.
            // IMPORTANT: build the mock rows BEFORE the outer when(...) call -
            // multiItemRow() does its own stubbing, and Mockito gets confused
            // if those stubs run while an outer when(...) is still being chained.
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 2, 0, 0, "SKIPPED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            var step = nodes.stream().filter(n -> "mcp:step".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(step).containsEntry("status", "COMPLETED");
            assertThat(step).containsEntry("execution_count", 3);
            @SuppressWarnings("unchecked")
            var counts = (Map<String, Long>) step.get("status_counts");
            assertThat(counts).containsEntry("completed", 2L).containsEntry("skipped", 1L);
        }

        @Test
        @DisplayName("epoch detail - single-item node still shows execution_count=1, no status_counts")
        void epochDetail_singleItemNode_omitsStatusCounts() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            List<WorkflowStepDataEntity> rows = List.of(multiItemRow(0, 0, 0, 0, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildEpochDetailReport(
                    run, linearPlan(), 0, TENANT_ID);

            @SuppressWarnings("unchecked")
            var nodes = (List<Map<String, Object>>) result.get("nodes");
            var step = nodes.stream().filter(n -> "mcp:step".equals(n.get("node_id"))).findFirst().orElseThrow();
            assertThat(step).containsEntry("execution_count", 1);
            assertThat(step).doesNotContainKey("status_counts");
        }

        @Test
        @DisplayName("get_node_output - list mode when multiple items, returns items[] + status_counts, no output blob")
        void nodeOutput_listMode_whenMultipleItems() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storage0 = UUID.randomUUID();
            UUID storage1 = UUID.randomUUID();
            UUID storage2 = UUID.randomUUID();
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "COMPLETED", storage0, null, null),
                    multiItemRow(0, 1, 0, 0, "FAILED", storage1, "rate limit", null),
                    multiItemRow(0, 2, 0, 0, "SKIPPED", storage2, null, "Not routed to this branch"));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result).containsEntry("execution_count", 3);
            @SuppressWarnings("unchecked")
            var counts = (Map<String, Long>) result.get("status_counts");
            assertThat(counts)
                    .containsEntry("completed", 1L)
                    .containsEntry("failed", 1L)
                    .containsEntry("skipped", 1L);
            assertThat(result).doesNotContainKey("output"); // list mode keeps payload bounded
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(3);
            assertThat(items.get(0)).containsEntry("item_index", 0).containsEntry("status", "COMPLETED");
            assertThat(items.get(1)).containsEntry("item_index", 1).containsEntry("status", "FAILED")
                    .containsEntry("error", "rate limit");
            assertThat(items.get(2)).containsEntry("item_index", 2).containsEntry("status", "SKIPPED")
                    .containsEntry("skip_reason", "Not routed to this branch");
            verify(stepOutputService, never()).loadRawOutput(any(), any()); // no per-item blob fetch in list mode
        }

        @Test
        @DisplayName("get_node_output - list mode is deterministically ordered by (item_index, iteration, spawn) - guards the historical findFirst() arbitrariness")
        void nodeOutput_listMode_deterministicOrder() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // Repository returns rows in arbitrary order (id-DESC equivalent) -
            // the service must sort them before exposing to the agent.
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 5, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 3, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 0, 0, 0, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).extracting(i -> i.get("item_index"))
                    .containsExactly(0, 1, 3, 5);
        }

        @Test
        @DisplayName("get_node_output - item_index filter zooms into one item with full output")
        void nodeOutput_zoomMode_withItemIndexFilter() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageId = UUID.randomUUID();
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 0, 0, "FAILED", storageId, "rate limit", null),
                    multiItemRow(0, 2, 0, 0, "SKIPPED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                    .thenReturn(Map.of("error_payload", Map.of("code", 429)));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID, 1, null, null);

            assertThat(result).containsEntry("item_index", 1);
            assertThat(result).containsEntry("item_status", "FAILED");
            assertThat(result).containsEntry("error", "rate limit");
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.get("output");
            assertThat(output).containsKey("error_payload");
            assertThat(result).doesNotContainKey("items"); // zoom mode, not list
        }

        @Test
        @DisplayName("get_node_output - filter miss returns helpful note + counts so agent can re-call without filter")
        void nodeOutput_filterMiss_helpfulNote() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 0, 0, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID, 99, null, null);

            assertThat((String) result.get("note")).contains("item_index=99");
            assertThat(result).containsEntry("execution_count", 2);
            assertThat(result).doesNotContainKey("output");
        }

        @Test
        @DisplayName("get_node_output - single row zoom exposes selected_branch / condition_result / loop_iteration / skip_reason")
        void nodeOutput_singleRow_exposesRoutingFields() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            WorkflowStepDataEntity step = mock(WorkflowStepDataEntity.class);
            lenient().when(step.getEpoch()).thenReturn(0);
            lenient().when(step.getItemIndex()).thenReturn(0);
            lenient().when(step.getIteration()).thenReturn(0);
            lenient().when(step.getSpawn()).thenReturn(0);
            lenient().when(step.getStatus()).thenReturn("COMPLETED");
            lenient().when(step.getSelectedBranch()).thenReturn("category_promotions");
            lenient().when(step.getConditionResult()).thenReturn(true);
            lenient().when(step.getConditionExpression()).thenReturn("{{x}} > 0");
            lenient().when(step.getLoopIteration()).thenReturn(2);
            lenient().when(step.getLoopId()).thenReturn("loop:retry");
            lenient().when(step.getLoopExitReason()).thenReturn("MAX_ITERATIONS");
            lenient().when(step.getSkipReason()).thenReturn(null);
            lenient().when(step.getSkipSourceNode()).thenReturn(null);
            lenient().when(step.getToolId()).thenReturn("gmail/gmail-list-messages");
            lenient().when(step.getHttpStatus()).thenReturn(200);

            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result)
                    .containsEntry("selected_branch", "category_promotions")
                    .containsEntry("condition_result", true)
                    .containsEntry("condition_expression", "{{x}} > 0")
                    .containsEntry("loop_id", "loop:retry")
                    .containsEntry("loop_iteration", 2)
                    .containsEntry("loop_exit_reason", "MAX_ITERATIONS")
                    .containsEntry("tool_id", "gmail/gmail-list-messages")
                    .containsEntry("http_status", 200);
        }

        @Test
        @DisplayName("get_node_output - list mode surfaces multiple loop iterations of the same item")
        void nodeOutput_listMode_loopIterations() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // Same item (index=0) iterated 3 times by an enclosing loop -
            // common pattern for retry / for-each-page workflows.
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 1, 0, "FAILED", null, "transient 503", null),
                    multiItemRow(0, 0, 2, 0, "FAILED", null, "transient 503", null),
                    multiItemRow(0, 0, 3, 0, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(3);
            assertThat(items).extracting(i -> i.get("iteration")).containsExactly(1, 2, 3);
            assertThat(items).extracting(i -> i.get("status"))
                    .containsExactly("FAILED", "FAILED", "COMPLETED");
        }

        @Test
        @DisplayName("get_node_output - list mode surfaces multiple spawns of the same item (re-runs)")
        void nodeOutput_listMode_spawnReRuns() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // Three spawns of the same (item=0, iteration=0) - typical of a
            // step manually re-run from the editor inspector.
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "FAILED", null, "first attempt", null),
                    multiItemRow(0, 0, 0, 1, "FAILED", null, "second attempt", null),
                    multiItemRow(0, 0, 0, 2, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(3);
            // spawn=0 row hides the field (signal-dense default); spawn=1 and =2 surface it.
            assertThat(items.get(0)).doesNotContainKey("spawn");
            assertThat(items.get(1)).containsEntry("spawn", 1);
            assertThat(items.get(2)).containsEntry("spawn", 2);
            // Status counts aggregate across spawns
            @SuppressWarnings("unchecked")
            var counts = (Map<String, Long>) result.get("status_counts");
            assertThat(counts).containsEntry("failed", 2L).containsEntry("completed", 1L);
        }

        @Test
        @DisplayName("get_node_output - partial filter (iteration alone) matching >1 row falls back to list mode of just the matches, not an arbitrary findFirst()")
        void nodeOutput_partialFilter_multiMatch_fallsBackToList() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // 3 items × 2 iterations each = 6 rows. Filter iteration=2 alone
            // matches 3 rows (one per item). Without auto-fall-back, the agent
            // would see one arbitrary row - exactly the bug we're guarding.
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 1, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 0, 2, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 1, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 2, 0, "FAILED", null, "boom", null),
                    multiItemRow(0, 2, 1, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 2, 2, 0, "COMPLETED", null, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID, null, 2, null);

            assertThat(result).containsEntry("execution_count", 3);
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(3);
            assertThat(items).extracting(i -> i.get("item_index")).containsExactly(0, 1, 2);
            assertThat((String) result.get("note")).contains("matched 3 rows").contains("Add another filter");
            assertThat(result).doesNotContainKey("output"); // not zoom mode
        }

        @Test
        @DisplayName("get_node_output - combined item_index + iteration filter zooms into one loop iteration of one item")
        void nodeOutput_combinedFilter_itemAndIteration() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            UUID storageHit = UUID.randomUUID();
            // 2 items × 2 iterations = 4 rows; the agent wants (item=1, iter=2).
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 1, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 0, 2, 0, "COMPLETED", null, null, null),
                    multiItemRow(0, 1, 1, 0, "FAILED", null, "wrong attempt", null),
                    multiItemRow(0, 1, 2, 0, "COMPLETED", storageHit, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);
            when(stepOutputService.loadRawOutput(storageHit, TENANT_ID))
                    .thenReturn(Map.of("payload", "right one"));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID, 1, 2, null);

            assertThat(result).containsEntry("item_index", 1);
            assertThat(result).containsEntry("iteration", 2);
            assertThat(result).containsEntry("item_status", "COMPLETED");
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.get("output");
            assertThat(output).containsEntry("payload", "right one");
        }

        @Test
        @DisplayName("get_node_output - propagated SKIP without any persisted row - agent gets node-level status + helpful note, no fake item data")
        void nodeOutput_propagatedSkipNoRow() throws Exception {
            // EpochState says SKIPPED, but no per-item event was persisted -
            // happens when skip propagation walks from a parent and the
            // downstream node never reached the per-item executor at all.
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of("mcp:step"),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of());

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result).containsEntry("status", "SKIPPED");
            assertThat(result).containsEntry("output", null);
            assertThat((String) result.get("note")).contains("No step data found");
            assertThat(result).doesNotContainKey("items"); // not list mode either
        }

        @Test
        @DisplayName("get_node_output - repository exception degrades gracefully to empty list (logged, no thrown)")
        void nodeOutput_repoException_degradesGracefully() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenThrow(new RuntimeException("DB pool exhausted"));

            // Must not throw - agent surface always returns a structured response.
            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result).containsEntry("status", "COMPLETED");
            assertThat(result).containsEntry("output", null);
            assertThat((String) result.get("note")).contains("No step data found");
        }

        @Test
        @DisplayName("get_node_output - merge node surfaces merge_strategy + merge_received_branches + merge_skipped_branches in zoom")
        void nodeOutput_singleRow_exposesMergeFields() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            WorkflowStepDataEntity step = mock(WorkflowStepDataEntity.class);
            lenient().when(step.getEpoch()).thenReturn(0);
            lenient().when(step.getItemIndex()).thenReturn(0);
            lenient().when(step.getStatus()).thenReturn("COMPLETED");
            lenient().when(step.getMergeStrategy()).thenReturn("collect");
            lenient().when(step.getMergeReceivedBranches()).thenReturn(List.of("branch_a", "branch_b"));
            lenient().when(step.getMergeSkippedBranches()).thenReturn(List.of("branch_c"));

            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result)
                    .containsEntry("merge_strategy", "collect")
                    .containsEntry("merge_received_branches", List.of("branch_a", "branch_b"))
                    .containsEntry("merge_skipped_branches", List.of("branch_c"));
        }

        @Test
        @DisplayName("regression - list mode replaces the old findFirst() arbitrary-item bug: with 3 rows, the agent gets ALL three items (no output blob), not 1")
        void nodeOutput_listMode_replacesFindFirstBug() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of("mcp:step"),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of(),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            // Pre-fix behaviour: findFirst() returned 1 row; the agent saw 1
            // arbitrary item among N. Post-fix: list mode returns ALL N items
            // and the output blob is NOT loaded (kept for the zoom call).
            UUID storage = UUID.randomUUID();
            List<WorkflowStepDataEntity> rows = List.of(
                    multiItemRow(0, 0, 0, 0, "COMPLETED", storage, null, null),
                    multiItemRow(0, 1, 0, 0, "COMPLETED", storage, null, null),
                    multiItemRow(0, 2, 0, 0, "COMPLETED", storage, null, null));
            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(rows);

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(3); // not 1 (the historical bug)
            assertThat(result).doesNotContainKey("output"); // blob deferred to zoom
            verify(stepOutputService, never()).loadRawOutput(any(), any());
        }

        @Test
        @DisplayName("get_node_output - skip lineage (skip_reason + skip_source_node) reaches agent on a SKIPPED row")
        void nodeOutput_singleRow_exposesSkipLineage() throws Exception {
            WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);

            String epochStateJson = mapper.writeValueAsString(Map.of(
                    "completedNodeIds", List.of(),
                    "failedNodeIds", List.of(),
                    "skippedNodeIds", List.of("mcp:step"),
                    "runningNodeIds", List.of(),
                    "readyNodeIds", List.of(),
                    "awaitingSignalNodeIds", List.of()
            ));
            var header = new EpochHeaderRow(epochStateJson, false,
                    java.time.Instant.now(), java.time.Instant.now(), "trigger:start", 1000L);
            when(epochService.getEpochHeader(RUN_ID, 0)).thenReturn(header);

            WorkflowStepDataEntity step = mock(WorkflowStepDataEntity.class);
            lenient().when(step.getEpoch()).thenReturn(0);
            lenient().when(step.getItemIndex()).thenReturn(0);
            lenient().when(step.getStatus()).thenReturn("SKIPPED");
            lenient().when(step.getSkipReason()).thenReturn("Not routed to this branch");
            lenient().when(step.getSkipSourceNode()).thenReturn("core:is_new");

            when(stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:step"))
                    .thenReturn(List.of(step));

            Map<String, Object> result = service.buildNodeOutputReport(
                    run, linearPlan(), 0, "mcp:step", TENANT_ID);

            assertThat(result)
                    .containsEntry("skip_reason", "Not routed to this branch")
                    .containsEntry("skip_source_node", "core:is_new");
        }
    }

    /**
     * Build a fully-stubbed step entity for split-aware tests. Covers all the
     * identity dimensions ({@code itemIndex}, {@code iteration}, {@code spawn})
     * plus the routing fields the agent needs ({@code status}, {@code error},
     * {@code skipReason}, optional {@code outputStorageId}). Use {@code null}
     * for fields a test does not care about.
     */
    private WorkflowStepDataEntity multiItemRow(int epoch, int itemIndex, int iteration, int spawn,
                                                  String status, UUID outputStorageId,
                                                  String errorMessage, String skipReason) {
        WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
        lenient().when(entity.getEpoch()).thenReturn(epoch);
        lenient().when(entity.getItemIndex()).thenReturn(itemIndex);
        lenient().when(entity.getIteration()).thenReturn(iteration);
        lenient().when(entity.getSpawn()).thenReturn(spawn);
        lenient().when(entity.getStatus()).thenReturn(status);
        lenient().when(entity.getOutputStorageId()).thenReturn(outputStorageId);
        lenient().when(entity.getErrorMessage()).thenReturn(errorMessage);
        lenient().when(entity.getSkipReason()).thenReturn(skipReason);
        return entity;
    }
}
