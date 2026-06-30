package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotService")
class SnapshotServiceTest {

    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private SnapshotService service;

    @BeforeEach
    void setUp() {
        // Phase B.1 (archi-refoundation 2026-05-04) - SnapshotService now requires
        // WorkflowRunRepository (DB-as-truth terminal check) and Caffeine TTL configs.
        service = new SnapshotService(stateSnapshotService, streamingService, runningNodeTracker,
                workflowEpochService, runRepository, 60L, 1800L);
    }

    @Nested
    @DisplayName("Running overlay")
    class RunningOverlayTests {

        @Test
        @DisplayName("Should overlay in-memory running counts on DB snapshot")
        void shouldOverlayRunningCounts() {
            // DB has a completed node
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            // In-memory: step2 is currently running
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of("mcp:step2", 1));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");

            // Should have 2 steps: completed from DB + running from overlay
            assertEquals(2, steps.size());

            // Find step2 (running from overlay)
            Map<String, Object> runningStep = steps.stream()
                .filter(s -> "mcp:step2".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("running", runningStep.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> runningCounts = (Map<String, Object>) runningStep.get("statusCounts");
            assertEquals(1, runningCounts.get("running"));

            // Find step1 (completed from DB, running=0 from overlay)
            Map<String, Object> completedStep = steps.stream()
                .filter(s -> "mcp:step1".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("completed", completedStep.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> completedCounts = (Map<String, Object>) completedStep.get("statusCounts");
            assertEquals(0, completedCounts.get("running"));
            assertEquals(1, completedCounts.get("completed"));
        }

        @Test
        @DisplayName("Should show running status when node has both running overlay and DB counts")
        void shouldShowRunningWhenBothOverlayAndDb() {
            // DB has 2 completed items for step1
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1")
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            // In-memory: 1 item still running on step1
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of("mcp:step1", 1));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");

            Map<String, Object> step = steps.stream()
                .filter(s -> "mcp:step1".equals(s.get("id")))
                .findFirst().orElseThrow();
            // DB says completed → overlay is stale → status should be completed
            assertEquals("completed", step.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) step.get("statusCounts");
            assertEquals(0, counts.get("running")); // Stale overlay ignored
            assertEquals(2, counts.get("completed"));
        }

        @ParameterizedTest
        @EnumSource(value = com.apimarketplace.orchestrator.domain.workflow.RunStatus.class,
            names = {"COMPLETED", "FAILED", "PARTIAL_SUCCESS", "CANCELLED", "TIMEOUT", "SKIPPED"})
        @DisplayName("Should NOT overlay running counts once the run is terminal - a finished run never paints a node running")
        void shouldNotOverlayRunningCountsWhenRunTerminal(
                com.apimarketplace.orchestrator.domain.workflow.RunStatus terminalStatus) {
            // DB: the agent node already completed.
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("agent:writer");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            // The run has reached a terminal state. A stale Redis overlay entry may
            // still linger inside the 1h TTL (e.g. a dropped markCompleted) - it must
            // be ignored: a finished run has nothing running by definition. Covers
            // every terminal RunStatus, not just COMPLETED, since the guard keys on
            // RunStatus.isTerminal().
            when(runRepository.findStatusByRunIdPublic("run-1"))
                .thenReturn(java.util.Optional.of(terminalStatus));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");

            // The node shows completed, never running.
            Map<String, Object> step = steps.stream()
                .filter(s -> "agent:writer".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("completed", step.get("status"));

            // The snapshot reports zero running nodes for a terminal run.
            @SuppressWarnings("unchecked")
            List<String> runningStepIds = (List<String>) snapshot.get("runningStepIds");
            assertTrue(runningStepIds.isEmpty(), "A terminal run must report zero running nodes");

            // The stale Redis overlay is short-circuited entirely - never even queried.
            verify(runningNodeTracker, never()).getRunningCountsAcrossEpochs(anyString());
        }

        @Test
        @DisplayName("Should work with empty running overlay")
        void shouldWorkWithEmptyOverlay() {
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");
            assertEquals(1, steps.size());
            assertEquals("completed", steps.get(0).get("status"));
        }

        @Test
        @DisplayName("Should skip null runId")
        void shouldSkipNullRunId() {
            service.sendSnapshot(null);
            verifyNoInteractions(stateSnapshotService);
            verifyNoInteractions(streamingService);
        }

        @Test
        @DisplayName("Should overlay RUNNING status on edges when target node is running")
        void shouldOverlayRunningOnEdgesWhenTargetIsRunning() {
            // DB: edge from trigger:start -> mcp:step1 with 1 completed traversal
            // DB: edge from mcp:step1 -> mcp:step2 with 1 completed traversal
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:start")
                .markNodeCompleted("mcp:step1")
                .incrementEdge("trigger:start", "mcp:step1", "COMPLETED")
                .incrementEdge("mcp:step1", "mcp:step2", "COMPLETED");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            // In-memory: step2 is currently running
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of("mcp:step2", 1));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) snapshot.get("edges");

            // Edge to running node (mcp:step1 -> mcp:step2) should have running=1
            Map<String, Object> edgeToRunning = edges.stream()
                .filter(e -> "mcp:step2".equals(e.get("to")))
                .findFirst().orElseThrow();
            assertEquals(1, edgeToRunning.get("running"), "Edge to running node should have running=1");
            assertEquals(1, edgeToRunning.get("completed"), "Edge should still show completed count");

            // Edge to completed node (trigger:start -> mcp:step1) should have running=0
            Map<String, Object> edgeToCompleted = edges.stream()
                .filter(e -> "mcp:step1".equals(e.get("to")))
                .findFirst().orElseThrow();
            assertEquals(0, edgeToCompleted.get("running"), "Edge to completed node should have running=0");
            assertEquals(1, edgeToCompleted.get("completed"));
        }
    }

    @Nested
    @DisplayName("Pending signals with epoch")
    class PendingSignalsEpochTests {

        @Mock private UnifiedSignalService unifiedSignalService;

        @BeforeEach
        void injectSignalService() throws Exception {
            Field field = SnapshotService.class.getDeclaredField("unifiedSignalService");
            field.setAccessible(true);
            field.set(service, unifiedSignalService);
        }

        private SignalWaitEntity buildSignal(long id, String nodeId, SignalType type, int epoch) {
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(id);
            entity.setRunId("run-1");
            entity.setNodeId(nodeId);
            entity.setSignalType(type);
            entity.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
            entity.setItemId("main");
            entity.setEpoch(epoch);
            entity.setCreatedAt(Instant.parse("2026-03-04T10:00:00Z"));
            return entity;
        }

        @Test
        @DisplayName("Should include epoch field in pending signals")
        void shouldIncludeEpochInPendingSignals() {
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity sig0 = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig3 = buildSignal(2L, "core:approval", SignalType.USER_APPROVAL, 3);
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(sig0, sig3));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) snapshot.get("pendingSignals");

            assertNotNull(pendingSignals);
            assertEquals(2, pendingSignals.size());

            // Check epoch field presence and values
            assertEquals(0, pendingSignals.get(0).get("epoch"));
            assertEquals(3, pendingSignals.get(1).get("epoch"));

            // Also verify other fields still present
            assertEquals("core:approval", pendingSignals.get(0).get("nodeId"));
            assertEquals("USER_APPROVAL", pendingSignals.get(0).get("signalType"));
            assertEquals("PENDING", pendingSignals.get(0).get("status"));
        }

        @Test
        @DisplayName("Should return empty pendingSignals when no active signals")
        void shouldReturnEmptyWhenNoSignals() {
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of());

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");

            assertNotNull(pendingSignals);
            assertTrue(pendingSignals.isEmpty());
        }

        @Test
        @DisplayName("Should include epoch for different signal types")
        void shouldIncludeEpochForAllSignalTypes() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity approval = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 2);
            SignalWaitEntity timer = buildSignal(2L, "core:wait", SignalType.WAIT_TIMER, 5);
            timer.setExpiresAt(Instant.parse("2026-03-04T11:00:00Z"));
            SignalWaitEntity iface = buildSignal(3L, "interface:form", SignalType.INTERFACE_SIGNAL, 7);
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(approval, timer, iface));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");

            assertEquals(3, pendingSignals.size());
            assertEquals(2, pendingSignals.get(0).get("epoch")); // approval
            assertEquals(5, pendingSignals.get(1).get("epoch")); // timer
            assertEquals(7, pendingSignals.get(2).get("epoch")); // interface
        }

        @Test
        @DisplayName("Should include itemContext with the persisted splitItemData in pending signals (split context)")
        void shouldIncludeItemContextWhenSplitItemDataPresent() {
            // Arrange: split-context approval carrying per-item data persisted at registration
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity sig = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            Map<String, Object> itemData = Map.of("name", "Order #42", "amount", 199);
            sig.setSplitItemData(itemData);
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(sig));

            // Act
            service.sendSnapshot("run-1");

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");
            assertEquals(itemData, pendingSignals.get(0).get("itemContext"));
        }

        @Test
        @DisplayName("REGRESSION: strips cross-pod restoration keys from itemContext in the WS snapshot (kept O(N) per push)")
        void stripsRestorationKeysFromItemContext() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity sig = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            sig.setSplitItemData(Map.of(
                "current_item", Map.of("name", "Order #42"),
                "current_index", 1,
                "splitNodeId", "core:split_orders",
                "items", List.of("a", "b", "c"),
                "itemIndex", 1,
                "workflowItemIndex", 0));
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(sig));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemContext = (Map<String, Object>) pendingSignals.get(0).get("itemContext");
            assertEquals(Map.of("current_item", Map.of("name", "Order #42"), "current_index", 1), itemContext);
            assertFalse(itemContext.containsKey("items"), "full items list must not be streamed in every snapshot");
        }

        @Test
        @DisplayName("Should omit the itemContext key when splitItemData is null or empty")
        void shouldOmitItemContextWhenSplitItemDataNullOrEmpty() {
            // Arrange: one regular signal (null splitItemData) + one with an empty map
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity nullData = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            SignalWaitEntity emptyData = buildSignal(2L, "core:approval_2", SignalType.USER_APPROVAL, 0);
            emptyData.setSplitItemData(Map.of());
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(nullData, emptyData));

            // Act
            service.sendSnapshot("run-1");

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");
            assertFalse(pendingSignals.get(0).containsKey("itemContext"),
                "itemContext must be omitted when splitItemData is null");
            assertFalse(pendingSignals.get(1).containsKey("itemContext"),
                "itemContext must be omitted when splitItemData is empty");
        }

        @Test
        @DisplayName("Should include approvalContext (resolved contextTemplate) in pending signals when present")
        void shouldIncludeApprovalContextWhenPresent() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity sig = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            sig.setApprovalContext("Approve refund of 120 EUR?");
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(sig));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");
            assertEquals("Approve refund of 120 EUR?", pendingSignals.get(0).get("approvalContext"));
        }

        @Test
        @DisplayName("Should omit approvalContext when null or blank")
        void shouldOmitApprovalContextWhenNullOrBlank() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            SignalWaitEntity nullCtx = buildSignal(1L, "core:approval", SignalType.USER_APPROVAL, 0);
            SignalWaitEntity blankCtx = buildSignal(2L, "core:approval_2", SignalType.USER_APPROVAL, 0);
            blankCtx.setApprovalContext("   ");
            when(unifiedSignalService.getActiveSignals("run-1")).thenReturn(List.of(nullCtx, blankCtx));

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingSignals =
                (List<Map<String, Object>>) captor.getValue().get("pendingSignals");
            assertFalse(pendingSignals.get(0).containsKey("approvalContext"));
            assertFalse(pendingSignals.get(1).containsKey("approvalContext"));
        }
    }

    @Nested
    @DisplayName("SBS epoch-aware status")
    class SbsEpochAwareStatusTests {

        @Test
        @DisplayName("Should show 'pending' for nodes with historical counts but no state in active epoch")
        void shouldShowPendingForNodesWithHistoricalCountsOnly() {
            // Simulate: epoch 0 completed step1 and step2 (both in global NodeCounts).
            // Epoch 0 was closed (by closeAllActiveEpochs on trigger fire).
            // Epoch 1 opened (new trigger fire) - fresh EpochState.
            // step1 is in readyNodeIds (direct child of trigger in epoch 1).
            // step2 is NOT in any flat view of epoch 1 (downstream, not yet reached).
            // step2 has historical NodeCounts (completed=1) but should show "pending", not "completed".
            //
            // Build this by: first mark completed in epoch 0, then close epoch 0, then open epoch 1.
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:webhook", 0)            // epoch 0 active
                .markNodeCompleted("trigger:webhook", "mcp:step1") // completed in epoch 0 → NodeCounts
                .markNodeCompleted("trigger:webhook", "mcp:step2") // completed in epoch 0 → NodeCounts
                .closeAndPruneEpochForDag("trigger:webhook", 0, 1000) // close epoch 0
                .openEpochForDag("trigger:webhook", 1)            // open epoch 1 (fresh)
                .addReadyNode("trigger:webhook", "mcp:step1");    // step1 ready in epoch 1

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");

            // step1: in readyNodeIds → "pending" (ready for execution)
            Map<String, Object> step1 = steps.stream()
                .filter(s -> "mcp:step1".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("pending", step1.get("status"),
                    "Ready node should show 'pending' (execute button)");

            // step2: NOT in any flat view, but has NodeCounts(completed=1).
            // With active epoch, should show "pending" (not yet executed in current epoch).
            Map<String, Object> step2 = steps.stream()
                .filter(s -> "mcp:step2".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("pending", step2.get("status"),
                    "Node with only historical counts should show 'pending' when active epoch exists");
            // statusCounts should still have the historical data
            @SuppressWarnings("unchecked")
            Map<String, Object> step2Counts = (Map<String, Object>) step2.get("statusCounts");
            assertEquals(1, step2Counts.get("completed"),
                    "Historical completed count should be preserved in statusCounts");
        }

        @Test
        @DisplayName("Should show historical status when no active epochs (WAITING_TRIGGER with open epoch)")
        void shouldShowHistoricalStatusWhenEpochActiveButComplete() {
            // After SBS workflow completes: epoch is still active (not closed),
            // all nodes are in completedNodeIds (flat view). Should show "completed".
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:webhook", 0)
                .markNodeCompleted("trigger:webhook", "trigger:webhook")
                .markNodeCompleted("trigger:webhook", "mcp:step1")
                .addReadyNode("trigger:webhook", "trigger:webhook"); // trigger ready for next fire

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");

            // step1: in completedNodeIds (flat view) + isInFlatView=true → "completed"
            Map<String, Object> step1 = steps.stream()
                .filter(s -> "mcp:step1".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("completed", step1.get("status"),
                    "Node in completedNodeIds should show 'completed' even with active epoch");

            // trigger: in readyNodeIds → "pending" (ready override)
            Map<String, Object> trigger = steps.stream()
                .filter(s -> "trigger:webhook".equals(s.get("id")))
                .findFirst().orElseThrow();
            assertEquals("pending", trigger.get("status"),
                    "Ready trigger should show 'pending'");
        }
    }

    @Nested
    @DisplayName("Skip-if-unchanged seq optimization (OOM 2026-05-06 hot-path #2)")
    class SkipIfUnchangedSeqTests {

        @Test
        @DisplayName("Two consecutive sendSnapshotImmediate calls with same seq → second is short-circuited")
        void secondSendSkipsWhenSeqUnchanged() {
            // Use sendSnapshotImmediate to bypass the 200ms throttle so both calls
            // run synchronously inside doSendSnapshot - the test asserts the
            // skip-if-unchanged guard, not the throttle.
            StateSnapshot s = StateSnapshot.empty().markNodeCompleted("mcp:step1");
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(s);

            service.sendSnapshotImmediate("run-1");
            service.sendSnapshotImmediate("run-1");

            // First call publishes; second sees seq unchanged → only 1 publish.
            verify(streamingService, times(1)).sendDirectSnapshot(eq("run-1"), any());
        }

        @Test
        @DisplayName("New seq after mutation → second send re-publishes (skip is unbound on seq change)")
        void newSeqTriggersFreshPublish() {
            // First call: seq=0 snapshot.
            StateSnapshot first = StateSnapshot.empty();
            // Second call: seq=1 snapshot (post-mutation, after saveSnapshot bumps).
            StateSnapshot second = StateSnapshot.empty().withIncrementedSeq();

            when(stateSnapshotService.getSnapshot("run-1"))
                .thenReturn(first)
                .thenReturn(second);

            service.sendSnapshotImmediate("run-1");
            service.sendSnapshotImmediate("run-1");

            // Both must publish - they carry different state.
            verify(streamingService, times(2)).sendDirectSnapshot(eq("run-1"), any());
        }

        @Test
        @DisplayName("cleanupRun resets the lastPublishedSeq map → next send re-publishes")
        void cleanupResetsTheSkipMemory() {
            StateSnapshot s = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(s);

            service.sendSnapshotImmediate("run-1");  // publishes (first time)
            service.sendSnapshotImmediate("run-1");  // skipped (seq unchanged)
            service.cleanupRun("run-1");              // forgets lastPublishedSeq
            service.sendSnapshotImmediate("run-1");  // publishes (memory was reset)

            // Note: cleanupRun pre-warms terminatedRunsCache for actually-terminal
            // runs (which would short-circuit the third call). With no
            // findStatusByRunIdPublic mock, the cache stays cold → the third call
            // proceeds to doSendSnapshot.
            verify(streamingService, times(2)).sendDirectSnapshot(eq("run-1"), any());
        }
    }

    @Nested
    @DisplayName("PAUSED/WAITING_TRIGGER short-circuit (P2.1.5)")
    class PausedShortCircuit {

        @Test
        @DisplayName("PAUSED run skips RunningNodeTracker.getRunningCounts and emits empty running overlay")
        void pausedRunSkipsRedisRead() {
            // Pre-fix behavior: even paused runs hit Redis. Stale entries inside the
            // 1h hash TTL would surface as ghost-running nodes in SSE.
            // Post-fix: status check returns empty without touching Redis.
            StateSnapshot dbSnapshot = StateSnapshot.empty()
                .markNodeCompleted("mcp:step1");

            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runRepository.findStatusByRunIdPublic("run-1"))
                .thenReturn(java.util.Optional.of(com.apimarketplace.orchestrator.domain.workflow.RunStatus.PAUSED));

            service.sendSnapshot("run-1");

            // The Redis read MUST NOT happen for PAUSED runs.
            verify(runningNodeTracker, never()).getRunningCountsAcrossEpochs(eq("run-1"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq("run-1"), captor.capture());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) captor.getValue().get("steps");
            // No running overlay was applied - only the DB-completed step shows.
            assertEquals(1, steps.size());
            assertEquals("completed", steps.get(0).get("status"));
        }

        @Test
        @DisplayName("WAITING_TRIGGER run skips RunningNodeTracker.getRunningCounts (same semantics as PAUSED)")
        void waitingTriggerRunSkipsRedisRead() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runRepository.findStatusByRunIdPublic("run-1"))
                .thenReturn(java.util.Optional.of(com.apimarketplace.orchestrator.domain.workflow.RunStatus.WAITING_TRIGGER));

            service.sendSnapshot("run-1");

            verify(runningNodeTracker, never()).getRunningCountsAcrossEpochs(eq("run-1"));
        }

        @Test
        @DisplayName("RUNNING status does NOT short-circuit - Redis is read normally")
        void runningStatusReadsRedis() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            when(runRepository.findStatusByRunIdPublic("run-1"))
                .thenReturn(java.util.Optional.of(com.apimarketplace.orchestrator.domain.workflow.RunStatus.RUNNING));
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of("mcp:step1", 1));

            service.sendSnapshot("run-1");

            verify(runningNodeTracker).getRunningCountsAcrossEpochs("run-1");
        }

        @Test
        @DisplayName("Missing run row falls through to Redis read (defensive: don't lose data on cache miss)")
        void missingRunFallsThroughToRedis() {
            StateSnapshot dbSnapshot = StateSnapshot.empty();
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(dbSnapshot);
            // findStatusByRunIdPublic returns Optional.empty() - no special case;
            // the short-circuit only triggers on PAUSED/WAITING_TRIGGER, not on absence.
            when(runRepository.findStatusByRunIdPublic("run-1"))
                .thenReturn(java.util.Optional.empty());
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            verify(runningNodeTracker).getRunningCountsAcrossEpochs("run-1");
        }
    }
}
