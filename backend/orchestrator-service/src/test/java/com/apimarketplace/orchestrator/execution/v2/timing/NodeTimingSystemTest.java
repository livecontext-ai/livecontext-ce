package com.apimarketplace.orchestrator.execution.v2.timing;

import com.apimarketplace.orchestrator.domain.execution.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for the generic node timing system.
 *
 * Covers:
 * - NodeCounts lastExecutionTimeMs tracking
 * - Signal resolution with duration (UserApproval, Interface, Wait)
 * - Engine-level timing (withDuration)
 * - SnapshotService startTime computation
 * - Multi-epoch duration correctness
 * - Backwards compatibility with old snapshots
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Node Timing System")
class NodeTimingSystemTest {

    // ========================================================================
    // 1. NodeCounts - lastExecutionTimeMs tracking
    // ========================================================================

    @Nested
    @DisplayName("NodeCounts.lastExecutionTimeMs")
    class NodeCountsLastExecutionTimeTests {

        @Test
        @DisplayName("zero() should have 0 lastExecutionTimeMs")
        void zeroShouldHaveZeroLastExec() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero();
            assertEquals(0L, counts.lastExecutionTimeMs());
            assertEquals(0L, counts.totalExecutionTimeMs());
            assertEquals(0L, counts.lastEndTimeMs());
        }

        @Test
        @DisplayName("single increment should set lastExecutionTimeMs = durationMs")
        void singleIncrementSetsLastExec() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L);

            assertEquals(500L, counts.lastExecutionTimeMs());
            assertEquals(500L, counts.totalExecutionTimeMs());
        }

        @Test
        @DisplayName("multiple increments: lastExecutionTimeMs = most recent, totalExecutionTimeMs = sum")
        void multipleIncrementsSeparateLastFromTotal() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)  // epoch 0
                .incrementWithTiming("COMPLETED", 300L)  // epoch 1
                .incrementWithTiming("COMPLETED", 200L); // epoch 2

            assertEquals(200L, counts.lastExecutionTimeMs(), "last should be 200ms (most recent)");
            assertEquals(1000L, counts.totalExecutionTimeMs(), "total should be 500+300+200=1000ms");
            assertEquals(3, counts.completed());
        }

        @Test
        @DisplayName("FAILED status also tracks lastExecutionTimeMs")
        void failedAlsoTracksLastExec() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)
                .incrementWithTiming("FAILED", 800L);

            assertEquals(800L, counts.lastExecutionTimeMs(), "last should be from the FAILED execution");
            assertEquals(1300L, counts.totalExecutionTimeMs(), "total should sum both");
        }

        @Test
        @DisplayName("SKIPPED status does NOT update lastExecutionTimeMs")
        void skippedDoesNotUpdateLastExec() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)
                .incrementWithTiming("SKIPPED", 100L);

            assertEquals(500L, counts.lastExecutionTimeMs(), "skipped should not change last");
            assertEquals(500L, counts.totalExecutionTimeMs(), "skipped should not change total");
        }

        @Test
        @DisplayName("increment() with 0L should NOT update lastExecutionTimeMs")
        void incrementWithZeroDurationPreservesLastExec() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)
                .increment("COMPLETED"); // equivalent to incrementWithTiming("COMPLETED", 0L)

            assertEquals(500L, counts.lastExecutionTimeMs(), "0-duration increment should preserve last");
            assertEquals(500L, counts.totalExecutionTimeMs(), "0-duration increment should preserve total");
            assertEquals(2, counts.completed());
        }

        @Test
        @DisplayName("displayDurationMs() prefers lastExecutionTimeMs when available")
        void displayDurationPrefersLast() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)
                .incrementWithTiming("COMPLETED", 300L);

            assertEquals(300L, counts.displayDurationMs(), "should return lastExecutionTimeMs");
        }

        @Test
        @DisplayName("displayDurationMs() falls back to totalExecutionTimeMs when lastExecutionTimeMs=0")
        void displayDurationFallsBackToTotal() {
            // Simulate old snapshot: has totalExecutionTimeMs but lastExecutionTimeMs=0
            StateSnapshot.NodeCounts oldCounts = new StateSnapshot.NodeCounts(0, 2, 0, 0, 800L, 123456L, 0L);

            assertEquals(800L, oldCounts.displayDurationMs(), "should fall back to total");
        }
    }

    // ========================================================================
    // 2. Signal resolution with duration - generic for all signal types
    // ========================================================================

    @Nested
    @DisplayName("Signal resolution timing")
    class SignalResolutionTimingTests {

        private static final String TRIGGER_ID = "trigger:manual";

        @Test
        @DisplayName("UserApproval: resolveAwaitingSignal should record wait duration in NodeCounts")
        void userApprovalShouldRecordWaitDuration() {
            // Simulate: UserApproval node yielded, user took 30 seconds to approve
            long waitDurationMs = 30_000L;

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approval")
                .resolveAwaitingSignal(TRIGGER_ID, "core:approval", waitDurationMs);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approval");
            assertEquals(1, counts.completed());
            assertEquals(waitDurationMs, counts.totalExecutionTimeMs());
            assertEquals(waitDurationMs, counts.lastExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);
        }

        @Test
        @DisplayName("Interface: resolveAwaitingSignal should record interaction duration")
        void interfaceShouldRecordInteractionDuration() {
            // Simulate: Interface node displayed, user interacted for 2 minutes
            long interactionDurationMs = 120_000L;

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "interface:form")
                .resolveAwaitingSignal(TRIGGER_ID, "interface:form", interactionDurationMs);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("interface:form");
            assertEquals(1, counts.completed());
            assertEquals(interactionDurationMs, counts.totalExecutionTimeMs());
            assertEquals(interactionDurationMs, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Wait timer: resolveAwaitingSignal should record wait duration")
        void waitTimerShouldRecordWaitDuration() {
            // Simulate: Wait node waited for 10 seconds
            long waitDurationMs = 10_000L;

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "core:wait")
                .resolveAwaitingSignal(TRIGGER_ID, "core:wait", waitDurationMs);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:wait");
            assertEquals(1, counts.completed());
            assertEquals(waitDurationMs, counts.totalExecutionTimeMs());
            assertEquals(waitDurationMs, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Epoch-scoped resolveAwaitingSignal should record duration")
        void epochScopedResolveRecordsDuration() {
            long waitDurationMs = 5_000L;

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "core:gate", 0)
                .resolveAwaitingSignal(TRIGGER_ID, "core:gate", 0, waitDurationMs);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:gate");
            assertEquals(1, counts.completed());
            assertEquals(waitDurationMs, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Multiple signal resolutions across epochs should track last and total")
        void multipleSignalResolutionsAcrossEpochs() {
            // Epoch 0: approval took 30s
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approval")
                .resolveAwaitingSignal(TRIGGER_ID, "core:approval", 30_000L);

            // Epoch 1: approval took 10s
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approval")
                .resolveAwaitingSignal(TRIGGER_ID, "core:approval", 10_000L);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approval");
            assertEquals(2, counts.completed());
            assertEquals(10_000L, counts.lastExecutionTimeMs(), "last = most recent (10s)");
            assertEquals(40_000L, counts.totalExecutionTimeMs(), "total = 30s + 10s");
        }

        @Test
        @DisplayName("resolveAwaitingSignal with 0 duration should still complete (backwards compat)")
        void resolveWithZeroDurationStillCompletes() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approval")
                .resolveAwaitingSignal(TRIGGER_ID, "core:approval", 0L);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approval");
            assertEquals(1, counts.completed());
            assertEquals(0L, counts.totalExecutionTimeMs());
            assertEquals(0L, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Flat resolveAwaitingSignal with duration should work")
        void flatResolveWithDuration() {
            long waitDurationMs = 15_000L;

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("core:approval")
                .resolveAwaitingSignal("core:approval", waitDurationMs);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approval");
            assertEquals(1, counts.completed());
            assertEquals(waitDurationMs, counts.lastExecutionTimeMs());
        }
    }

    // ========================================================================
    // 3. Engine-level timing (NodeExecutionResult.withDuration)
    // ========================================================================

    @Nested
    @DisplayName("NodeExecutionResult.withDuration")
    class WithDurationTests {

        @Test
        @DisplayName("withDuration overrides durationMs on success result")
        void overridesDurationOnSuccess() {
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));
            assertEquals(0L, result.durationMs(), "original should be 0");

            NodeExecutionResult timed = result.withDuration(1234L);
            assertEquals(1234L, timed.durationMs());
            assertEquals("mcp:step1", timed.nodeId());
            assertTrue(timed.isSuccess());
            assertEquals("value", timed.output().get("data"));
        }

        @Test
        @DisplayName("withDuration overrides durationMs on failure result")
        void overridesDurationOnFailure() {
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "timeout");
            NodeExecutionResult timed = result.withDuration(5000L);
            assertEquals(5000L, timed.durationMs());
            assertTrue(timed.isFailure());
            assertEquals("timeout", timed.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("withDuration preserves all fields except durationMs")
        void preservesAllFields() {
            Map<String, Object> meta = new HashMap<>();
            meta.put("signal_type", "WAIT_TIMER");
            NodeExecutionResult original = new NodeExecutionResult(
                "core:wait", NodeStatus.COMPLETED, Map.of("result", "ok"),
                Optional.of("info"), meta, 100L);

            NodeExecutionResult timed = original.withDuration(999L);
            assertEquals(999L, timed.durationMs());
            assertEquals("core:wait", timed.nodeId());
            assertEquals(NodeStatus.COMPLETED, timed.status());
            assertEquals("ok", timed.output().get("result"));
            assertEquals("info", timed.errorMessage().orElse(""));
            assertEquals("WAIT_TIMER", timed.metadata().get("signal_type"));
        }

        @Test
        @DisplayName("AWAITING_SIGNAL result should NOT be overridden by engine (test the contract)")
        void awaitingSignalContractTest() {
            // The engine should NOT call withDuration on AWAITING_SIGNAL results.
            // This test verifies the contract: awaitingSignal starts with 0 durationMs.
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:approval", SignalType.USER_APPROVAL, Map.of("timeout", 86400000L));

            assertEquals(0L, result.durationMs(), "awaitingSignal should start with 0 durationMs");
            assertTrue(result.isAwaitingSignal());
        }
    }

    // ========================================================================
    // 4. SnapshotService startTime computation
    // ========================================================================

    @Nested
    @DisplayName("SnapshotService timing output")
    class SnapshotServiceTimingTests {

        @Mock private StateSnapshotService stateSnapshotService;
        @Mock private WorkflowStreamingService streamingService;
        @Mock private RunningNodeTracker runningNodeTracker;
        @Mock private com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService workflowEpochService;
        @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

        private SnapshotService service;

        @BeforeEach
        void setUp() {
            // Phase B.1 (archi-refoundation 2026-05-04) - new constructor params
            service = new SnapshotService(stateSnapshotService, streamingService, runningNodeTracker,
                    workflowEpochService, runRepository, 60L, 1800L);
        }

        @Test
        @DisplayName("Single execution: startTime = endTime - lastExecutionTimeMs")
        void singleExecutionCorrectStartTime() {
            // Node executed once: 500ms duration, ended at specific time
            long endTimeMs = System.currentTimeMillis();
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 1, 0, 0, 500L, endTimeMs, 500L);

            StateSnapshot snapshot = buildSnapshotWithCounts("mcp:step1", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "mcp:step1");
            assertEquals(500L, step.get("executionTimeMs"));
            assertEquals(500L, step.get("totalExecutionTimeMs"));

            String startTime = (String) step.get("startTime");
            String endTime = (String) step.get("endTime");
            assertNotNull(startTime);
            assertNotNull(endTime);

            // Verify: startTime = endTime - 500ms
            long startMs = Instant.parse(startTime).toEpochMilli();
            long endMs = Instant.parse(endTime).toEpochMilli();
            assertEquals(500L, endMs - startMs, "duration should be endTime - startTime");
        }

        @Test
        @DisplayName("Multi-epoch: startTime reflects LAST execution, not cumulative")
        void multiEpochCorrectStartTime() {
            // Node executed 3 times: 500ms + 300ms + 200ms
            // lastExecutionTimeMs = 200ms (most recent), totalExecutionTimeMs = 1000ms
            long endTimeMs = System.currentTimeMillis();
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 3, 0, 0, 1000L, endTimeMs, 200L);

            StateSnapshot snapshot = buildSnapshotWithCounts("mcp:step1", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "mcp:step1");
            assertEquals(200L, step.get("executionTimeMs"), "should show last execution duration");
            assertEquals(1000L, step.get("totalExecutionTimeMs"), "should show cumulative");

            String startTime = (String) step.get("startTime");
            String endTime = (String) step.get("endTime");
            long startMs = Instant.parse(startTime).toEpochMilli();
            long endMs = Instant.parse(endTime).toEpochMilli();
            assertEquals(200L, endMs - startMs, "startTime should be based on LAST execution (200ms), not total (1000ms)");
        }

        @Test
        @DisplayName("UserApproval node: timing shows wait duration after signal resolution")
        void userApprovalTimingAfterResolution() {
            // UserApproval waited 30 seconds for approval
            long endTimeMs = System.currentTimeMillis();
            long waitDuration = 30_000L;
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 1, 0, 0, waitDuration, endTimeMs, waitDuration);

            StateSnapshot snapshot = buildSnapshotWithCounts("core:approval", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "core:approval");
            assertEquals(waitDuration, step.get("executionTimeMs"));

            String startTime = (String) step.get("startTime");
            String endTime = (String) step.get("endTime");
            long startMs = Instant.parse(startTime).toEpochMilli();
            long endMs = Instant.parse(endTime).toEpochMilli();
            assertEquals(waitDuration, endMs - startMs, "should show 30s wait duration");
        }

        @Test
        @DisplayName("Interface node: timing shows interaction duration after signal resolution")
        void interfaceTimingAfterResolution() {
            // Interface displayed for 2 minutes
            long endTimeMs = System.currentTimeMillis();
            long interactionDuration = 120_000L;
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 1, 0, 0, interactionDuration, endTimeMs, interactionDuration);

            StateSnapshot snapshot = buildSnapshotWithCounts("interface:form", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "interface:form");
            assertEquals(interactionDuration, step.get("executionTimeMs"));
        }

        @Test
        @DisplayName("Node with 0 duration (old signal path): no timing in output")
        void zeroDurationNoTiming() {
            // Old behavior: signal resolved without duration (backwards compat)
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 1, 0, 0, 0L, 0L, 0L);

            StateSnapshot snapshot = buildSnapshotWithCounts("core:approval", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "core:approval");
            assertNull(step.get("executionTimeMs"), "no timing for 0-duration node");
            assertNull(step.get("startTime"));
            assertNull(step.get("endTime"));
        }

        @Test
        @DisplayName("Backwards compat: old snapshot with lastExecutionTimeMs=0 falls back to total")
        void backwardsCompatFallbackToTotal() {
            // Simulate old snapshot: totalExecutionTimeMs=800, lastExecutionTimeMs=0
            long endTimeMs = System.currentTimeMillis();
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 2, 0, 0, 800L, endTimeMs, 0L);

            StateSnapshot snapshot = buildSnapshotWithCounts("mcp:step1", counts);
            when(stateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(runningNodeTracker.getRunningCountsAcrossEpochs("run-1")).thenReturn(Map.of());

            service.sendSnapshot("run-1");

            Map<String, Object> step = captureStep("run-1", "mcp:step1");
            assertEquals(800L, step.get("executionTimeMs"), "should fall back to totalExecutionTimeMs");
            assertEquals(800L, step.get("totalExecutionTimeMs"));
        }

        // Helpers

        private StateSnapshot buildSnapshotWithCounts(String nodeId, StateSnapshot.NodeCounts counts) {
            // Build a snapshot with specific NodeCounts by using the mutation API.
            // We need exact control over timing fields, so we use multiple markNodeCompleted
            // calls with specific durations that produce the desired NodeCounts.
            //
            // Alternative: use reflection to inject counts directly.
            try {
                StateSnapshot base = StateSnapshot.empty().markNodeCompleted("trigger:default", nodeId);

                var nodesField = StateSnapshot.class.getDeclaredField("nodes");
                nodesField.setAccessible(true);

                Map<String, StateSnapshot.NodeCounts> newNodes = new HashMap<>();
                newNodes.put(nodeId, counts);

                var dagsField = StateSnapshot.class.getDeclaredField("dags");
                dagsField.setAccessible(true);
                var edgesField = StateSnapshot.class.getDeclaredField("edges");
                edgesField.setAccessible(true);

                var method = StateSnapshot.class.getDeclaredMethod("fromDags",
                    int.class, long.class, Map.class, Map.class, Map.class);
                method.setAccessible(true);

                return (StateSnapshot) method.invoke(base,
                    base.getVersion(), base.getSeq(),
                    dagsField.get(base), newNodes, edgesField.get(base));
            } catch (Exception e) {
                throw new RuntimeException("Failed to build test snapshot", e);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> captureStep(String runId, String nodeId) {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(streamingService).sendDirectSnapshot(eq(runId), captor.capture());

            Map<String, Object> snapshot = captor.getValue();
            List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");
            assertNotNull(steps, "steps should not be null");

            return steps.stream()
                .filter(s -> nodeId.equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step not found: " + nodeId));
        }
    }

    // ========================================================================
    // 5. Multi-epoch scenarios
    // ========================================================================

    @Nested
    @DisplayName("Multi-epoch duration scenarios")
    class MultiEpochTimingTests {

        private static final String TRIGGER_ID = "trigger:webhook";

        @Test
        @DisplayName("MCP node across 3 epochs: NodeCounts tracks both last and total")
        void mcpNodeAcross3Epochs() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Epoch 0: node runs in 500ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:fetch", 0, 500L);
            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("mcp:fetch");
            assertEquals(500L, counts.lastExecutionTimeMs());
            assertEquals(500L, counts.totalExecutionTimeMs());
            assertEquals(1, counts.completed());

            // Epoch 1: node runs in 300ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:fetch", 1, 300L);
            counts = snapshot.getNodeCounts("mcp:fetch");
            assertEquals(300L, counts.lastExecutionTimeMs());
            assertEquals(800L, counts.totalExecutionTimeMs());
            assertEquals(2, counts.completed());

            // Epoch 2: node runs in 200ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:fetch", 2, 200L);
            counts = snapshot.getNodeCounts("mcp:fetch");
            assertEquals(200L, counts.lastExecutionTimeMs(), "should be ONLY the last execution");
            assertEquals(1000L, counts.totalExecutionTimeMs(), "should be cumulative");
            assertEquals(3, counts.completed());
        }

        @Test
        @DisplayName("UserApproval across epochs: each approval wait tracked separately")
        void userApprovalAcrossEpochs() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Epoch 0: approval took 60s
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approve", 0)
                .resolveAwaitingSignal(TRIGGER_ID, "core:approve", 0, 60_000L);

            // Epoch 1: approval took 5s (quicker this time)
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approve", 1)
                .resolveAwaitingSignal(TRIGGER_ID, "core:approve", 1, 5_000L);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approve");
            assertEquals(2, counts.completed());
            assertEquals(5_000L, counts.lastExecutionTimeMs(), "last = 5s (most recent)");
            assertEquals(65_000L, counts.totalExecutionTimeMs(), "total = 60s + 5s");
        }

        @Test
        @DisplayName("Interface across epochs: each interaction tracked")
        void interfaceAcrossEpochs() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Epoch 0: user interacted for 2 min
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "interface:dashboard", 0)
                .resolveAwaitingSignal(TRIGGER_ID, "interface:dashboard", 0, 120_000L);

            // Epoch 1: user interacted for 30s
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "interface:dashboard", 1)
                .resolveAwaitingSignal(TRIGGER_ID, "interface:dashboard", 1, 30_000L);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("interface:dashboard");
            assertEquals(2, counts.completed());
            assertEquals(30_000L, counts.lastExecutionTimeMs(), "last = 30s");
            assertEquals(150_000L, counts.totalExecutionTimeMs(), "total = 120s + 30s");
        }

        @Test
        @DisplayName("Mixed: MCP then signal then MCP - all durations tracked correctly")
        void mixedNodeTypes() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // mcp:fetch runs in 500ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:fetch", 0, 500L);

            // core:approval waits 30s
            snapshot = snapshot
                .markNodeAwaitingSignal(TRIGGER_ID, "core:approval", 0)
                .resolveAwaitingSignal(TRIGGER_ID, "core:approval", 0, 30_000L);

            // mcp:process runs in 200ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:process", 0, 200L);

            assertEquals(500L, snapshot.getNodeCounts("mcp:fetch").lastExecutionTimeMs());
            assertEquals(30_000L, snapshot.getNodeCounts("core:approval").lastExecutionTimeMs());
            assertEquals(200L, snapshot.getNodeCounts("mcp:process").lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Failed node retries: lastExecutionTimeMs shows most recent attempt")
        void failedRetries() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // First attempt: fails in 5s
            snapshot = snapshot.markNodeFailed(TRIGGER_ID, "mcp:api_call", 0, 5_000L);

            // Second attempt (retry): succeeds in 200ms
            snapshot = snapshot.markNodeCompleted(TRIGGER_ID, "mcp:api_call", 0, 200L);

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("mcp:api_call");
            assertEquals(1, counts.completed());
            assertEquals(1, counts.failed());
            assertEquals(200L, counts.lastExecutionTimeMs(), "last = successful retry (200ms)");
            assertEquals(5_200L, counts.totalExecutionTimeMs(), "total = 5000 + 200");
        }
    }

    // ========================================================================
    // 6. Backwards compatibility: JSON deserialization
    // ========================================================================

    @Nested
    @DisplayName("Backwards compatibility")
    class BackwardsCompatTests {

        @Test
        @DisplayName("NodeCounts JSON without lastExecutionTimeMs should deserialize with 0")
        void oldJsonDeserializesWithZeroLastExec() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            // Old JSON format: 6 fields, no lastExecutionTimeMs
            String oldJson = """
                {"running":0,"completed":2,"failed":0,"skipped":0,
                 "totalExecutionTimeMs":800,"lastEndTimeMs":1709571234000}
                """;

            StateSnapshot.NodeCounts counts = mapper.readValue(oldJson, StateSnapshot.NodeCounts.class);
            assertEquals(2, counts.completed());
            assertEquals(800L, counts.totalExecutionTimeMs());
            assertEquals(1709571234000L, counts.lastEndTimeMs());
            assertEquals(0L, counts.lastExecutionTimeMs(), "missing field should default to 0");
        }

        @Test
        @DisplayName("New JSON format with lastExecutionTimeMs should deserialize correctly")
        void newJsonDeserializesCorrectly() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            String newJson = """
                {"running":0,"completed":3,"failed":0,"skipped":0,
                 "totalExecutionTimeMs":1000,"lastEndTimeMs":1709571234000,
                 "lastExecutionTimeMs":200}
                """;

            StateSnapshot.NodeCounts counts = mapper.readValue(newJson, StateSnapshot.NodeCounts.class);
            assertEquals(3, counts.completed());
            assertEquals(1000L, counts.totalExecutionTimeMs());
            assertEquals(200L, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("NodeCounts serialization round-trip preserves all fields")
        void roundTripPreservesAllFields() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            StateSnapshot.NodeCounts original = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 500L)
                .incrementWithTiming("COMPLETED", 300L);

            String json = mapper.writeValueAsString(original);
            StateSnapshot.NodeCounts deserialized = mapper.readValue(json, StateSnapshot.NodeCounts.class);

            assertEquals(original.completed(), deserialized.completed());
            assertEquals(original.totalExecutionTimeMs(), deserialized.totalExecutionTimeMs());
            assertEquals(original.lastExecutionTimeMs(), deserialized.lastExecutionTimeMs());
            assertEquals(original.lastEndTimeMs(), deserialized.lastEndTimeMs());
        }

        @Test
        @DisplayName("displayDurationMs with old data (lastExec=0) should fall back to total")
        void displayDurationFallbackForOldData() {
            StateSnapshot.NodeCounts oldStyleCounts = new StateSnapshot.NodeCounts(0, 5, 0, 0, 2500L, 123456L, 0L);
            assertEquals(2500L, oldStyleCounts.displayDurationMs());
        }
    }

    // ========================================================================
    // 7. Integration-style: full flow from signal yield to snapshot output
    // ========================================================================

    @Nested
    @DisplayName("Full timing flow integration")
    class FullFlowTimingTests {

        @Mock private StateSnapshotService stateSnapshotService;
        @Mock private WorkflowStreamingService streamingService;
        @Mock private RunningNodeTracker runningNodeTracker;

        @Test
        @DisplayName("UserApproval: yield → resolve → snapshot shows correct timing")
        void userApprovalFullFlow() {
            // Step 1: Node yields with AWAITING_SIGNAL
            NodeExecutionResult yield = NodeExecutionResult.awaitingSignal(
                "core:approval", SignalType.USER_APPROVAL, Map.of());
            assertEquals(0L, yield.durationMs());
            assertTrue(yield.isAwaitingSignal());

            // Step 2: Signal resolves after 30s - this is what UnifiedSignalService does
            long waitDurationMs = 30_000L;
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("trigger:manual", "core:approval")
                .resolveAwaitingSignal("trigger:manual", "core:approval", waitDurationMs);

            // Step 3: Verify NodeCounts
            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:approval");
            assertEquals(1, counts.completed());
            assertEquals(waitDurationMs, counts.lastExecutionTimeMs());
            assertEquals(waitDurationMs, counts.totalExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);

            // Step 4: Verify displayDurationMs
            assertEquals(waitDurationMs, counts.displayDurationMs());
        }

        @Test
        @DisplayName("Interface: yield → resolve → snapshot shows interaction time")
        void interfaceFullFlow() {
            // Step 1: Node yields with INTERFACE_SIGNAL
            NodeExecutionResult yield = NodeExecutionResult.awaitingSignal(
                "interface:form", SignalType.INTERFACE_SIGNAL, Map.of("interface_id", "form-123"));
            assertEquals(0L, yield.durationMs());

            // Step 2: Signal resolves after 120s of interaction
            long interactionMs = 120_000L;
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("trigger:manual", "interface:form")
                .resolveAwaitingSignal("trigger:manual", "interface:form", interactionMs);

            // Step 3: Verify
            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("interface:form");
            assertEquals(1, counts.completed());
            assertEquals(interactionMs, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Regular MCP node: engine timing flows through to NodeCounts")
        void regularMcpNodeEngineTimingFlow() {
            // Step 1: Engine wraps execution with timing
            NodeExecutionResult result = NodeExecutionResult.success("mcp:fetch", Map.of("data", "response"));
            assertEquals(0L, result.durationMs());

            // Step 2: Engine overrides with measured duration
            result = result.withDuration(450L);
            assertEquals(450L, result.durationMs());

            // Step 3: This duration flows to markNodeCompleted
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:manual", "mcp:fetch", 0, result.durationMs());

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("mcp:fetch");
            assertEquals(450L, counts.lastExecutionTimeMs());
            assertEquals(450L, counts.totalExecutionTimeMs());
        }

        @Test
        @DisplayName("Decision node: engine timing gives non-zero duration even for control nodes")
        void decisionNodeGetsTiming() {
            // Before: decision nodes had 0 duration because they didn't measure internally
            // After: engine measures wall-clock time for ALL nodes

            NodeExecutionResult result = NodeExecutionResult.success(
                "core:check", Map.of("selectedBranch", "if"));
            // Engine overrides (typically ~1-5ms for decision evaluation)
            result = result.withDuration(3L);

            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:manual", "core:check", 0, result.durationMs());

            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:check");
            assertEquals(3L, counts.lastExecutionTimeMs());
        }
    }

    // ========================================================================
    // 8. Edge cases and robustness
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Very long duration (hours) should be handled correctly")
        void veryLongDuration() {
            long twoHoursMs = 2 * 60 * 60 * 1000L; // 7,200,000 ms

            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", twoHoursMs);

            assertEquals(twoHoursMs, counts.lastExecutionTimeMs());
            assertEquals(twoHoursMs, counts.totalExecutionTimeMs());
        }

        @Test
        @DisplayName("Very short duration (1ms) should be tracked")
        void veryShortDuration() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 1L);

            assertEquals(1L, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Negative duration should not update timing (treated as no timing)")
        void negativeDurationIgnored() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", -100L);

            assertEquals(0L, counts.lastExecutionTimeMs());
            assertEquals(0L, counts.totalExecutionTimeMs());
            assertEquals(1, counts.completed(), "should still increment completed count");
        }

        @Test
        @DisplayName("resetDag preserves global NodeCounts including lastExecutionTimeMs")
        void resetDagPreservesNodeCounts() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook", "mcp:step1", 0, 500L);

            StateSnapshot reset = snapshot.resetDag("trigger:webhook", 1);
            StateSnapshot.NodeCounts counts = reset.getNodeCounts("mcp:step1");
            assertEquals(1, counts.completed(), "NodeCounts should survive DAG reset");
            assertEquals(500L, counts.lastExecutionTimeMs());
        }

        @Test
        @DisplayName("Multiple nodes in same snapshot each track their own timing independently")
        void multipleNodesIndependentTiming() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:manual", "mcp:fast", 0, 50L)
                .markNodeCompleted("trigger:manual", "mcp:slow", 0, 5000L)
                .markNodeCompleted("trigger:manual", "core:decision", 0, 2L);

            assertEquals(50L, snapshot.getNodeCounts("mcp:fast").lastExecutionTimeMs());
            assertEquals(5000L, snapshot.getNodeCounts("mcp:slow").lastExecutionTimeMs());
            assertEquals(2L, snapshot.getNodeCounts("core:decision").lastExecutionTimeMs());
        }
    }
}
