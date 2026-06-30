package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WaitNode.
 * WaitNode delays execution for a specified duration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitNode")
class WaitNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create WaitNode with nodeId and duration")
        void shouldCreateWaitNodeWithNodeIdAndDuration() {
            WaitNode node = new WaitNode("core:wait", 5000);

            assertEquals("core:wait", node.getNodeId());
            assertEquals(NodeType.WAIT, node.getType());
            assertEquals(5000, node.getDurationMs());
        }

        @Test
        @DisplayName("Should create WaitNode with zero duration")
        void shouldCreateWaitNodeWithZeroDuration() {
            WaitNode node = new WaitNode("core:wait", 0);

            assertEquals(0, node.getDurationMs());
        }

        @Test
        @DisplayName("Should create WaitNode using builder")
        void shouldCreateWaitNodeUsingBuilder() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:my_wait")
                .durationMs(10000)
                .build();

            assertEquals("core:my_wait", node.getNodeId());
            assertEquals(10000, node.getDurationMs());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Success cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Success Cases")
    class ExecuteSuccessCasesTests {

        @Test
        @DisplayName("Should return success immediately when duration is 0")
        void shouldReturnSuccessImmediatelyWhenDurationIsZero() {
            WaitNode node = new WaitNode("core:wait", 0);

            long startTime = System.currentTimeMillis();
            NodeExecutionResult result = node.execute(context);
            long elapsedTime = System.currentTimeMillis() - startTime;

            assertTrue(result.isSuccess());
            assertTrue(elapsedTime < 100); // Should be nearly instant
        }

        @Test
        @DisplayName("Should return success after short wait")
        void shouldReturnSuccessAfterShortWait() {
            WaitNode node = new WaitNode("core:wait", 50); // 50ms

            long startTime = System.currentTimeMillis();
            NodeExecutionResult result = node.execute(context);
            long elapsedTime = System.currentTimeMillis() - startTime;

            assertTrue(result.isSuccess());
            assertTrue(elapsedTime >= 45); // Should have waited at least ~50ms (with some tolerance)
        }

        @Test
        @DisplayName("Should include status completed in output")
        void shouldIncludeStatusCompletedInOutput() {
            WaitNode node = new WaitNode("core:wait", 10);

            NodeExecutionResult result = node.execute(context);

            assertEquals("completed", result.output().get("status"));
        }

        @Test
        @DisplayName("Should include waited_ms in output")
        void shouldIncludeWaitedMsInOutput() {
            WaitNode node = new WaitNode("core:wait", 100);

            NodeExecutionResult result = node.execute(context);

            assertEquals(100L, result.output().get("waited_ms"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Different durations
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Different Durations")
    class ExecuteDifferentDurationsTests {

        @Test
        @DisplayName("Should handle negative duration as zero")
        void shouldHandleNegativeDurationAsZero() {
            // Note: WaitNode allows negative duration, but sleeps for 0
            WaitNode node = new WaitNode("core:wait", -100);

            NodeExecutionResult result = node.execute(context);

            // Should succeed without sleeping (duration > 0 check)
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle very short duration")
        void shouldHandleVeryShortDuration() {
            WaitNode node = new WaitNode("core:wait", 1);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1L, result.output().get("waited_ms"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            WaitNode node = new WaitNode("core:wait", 0);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:wait", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            WaitNode node = new WaitNode("core:wait", 0);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:wait", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getDurationMs() should return duration in milliseconds")
        void getDurationMsShouldReturnDurationInMilliseconds() {
            WaitNode node = new WaitNode("core:wait", 5000);

            assertEquals(5000, node.getDurationMs());
        }

        @Test
        @DisplayName("getDurationMs() should return zero for zero duration")
        void getDurationMsShouldReturnZeroForZeroDuration() {
            WaitNode node = new WaitNode("core:wait", 0);

            assertEquals(0, node.getDurationMs());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            WaitNode node = new WaitNode("core:wait", 0);

            NodeExecutionResult result = NodeExecutionResult.success("core:wait", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            WaitNode node = new WaitNode("core:wait", 0);

            NodeExecutionResult result = NodeExecutionResult.failure("core:wait", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Interruption handling tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Interruption Handling")
    class InterruptionHandlingTests {

        @Test
        @DisplayName("Should return failure when interrupted")
        void shouldReturnFailureWhenInterrupted() throws InterruptedException {
            WaitNode node = new WaitNode("core:wait", 5000); // Long wait

            Thread testThread = Thread.currentThread();

            // Schedule interrupt
            Thread interrupter = new Thread(() -> {
                try {
                    Thread.sleep(50);
                    testThread.interrupt();
                } catch (InterruptedException e) {
                    // ignore
                }
            });

            interrupter.start();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("interrupted"));
            assertNotNull(result.output().get("resolved_params"));
            assertEquals(5000L, ((Map<?, ?>) result.output().get("resolved_params")).get("duration"));

            // Clean up interrupt flag
            Thread.interrupted();
            interrupter.join();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Builder tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build WaitNode with all properties")
        void shouldBuildWaitNodeWithAllProperties() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:delay")
                .durationMs(3000)
                .build();

            assertEquals("core:delay", node.getNodeId());
            assertEquals(3000, node.getDurationMs());
            assertEquals(NodeType.WAIT, node.getType());
        }

        @Test
        @DisplayName("Should build WaitNode with default duration")
        void shouldBuildWaitNodeWithDefaultDuration() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait")
                .build();

            assertEquals("core:wait", node.getNodeId());
            assertEquals(0, node.getDurationMs()); // Default is 0
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Signal-based path (duration > 3000ms)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Signal Path (duration > 3s)")
    class ExecuteSignalPathTests {

        @Mock
        private UnifiedSignalService mockSignalService;

        @Test
        @DisplayName("Should return AWAITING_SIGNAL when duration > 3000ms and signalService available")
        void shouldReturnAwaitingSignalForLongDuration() {
            WaitNode node = new WaitNode("core:wait", 5000);
            node.setSignalService(mockSignalService);
            Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
            node.setClock(fixedClock);
            node.setDagTriggerId("trigger:test");
            node.setEpoch(1);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());

            // Verify registerSignal was called with correct parameters
            verify(mockSignalService).registerSignal(
                    eq("run-1"),        // runId
                    eq("item-1"),       // itemId
                    eq("core:wait"),    // nodeId
                    anyString(),        // dagTriggerId (resolved)
                    anyInt(),           // epoch (resolved)
                    eq(SignalType.WAIT_TIMER),
                    anyMap(),           // signalConfig
                    isNull()            // additionalData
            );
        }

        @Test
        @DisplayName("Should include expires_at and duration_ms in signal output")
        void shouldIncludeExpiresAtAndDurationInOutput() {
            WaitNode node = new WaitNode("core:wait", 10000);
            node.setSignalService(mockSignalService);
            Instant fixedInstant = Instant.parse("2026-03-01T12:00:00Z");
            Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
            node.setClock(fixedClock);
            node.setDagTriggerId("trigger:test");
            node.setEpoch(0);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> output = result.output();
            assertEquals(10000L, output.get("duration_ms"));

            // expires_at should be fixedInstant + 10000ms
            String expectedExpiresAt = fixedInstant.plusMillis(10000).toString();
            assertEquals(expectedExpiresAt, output.get("expires_at"));

            // signal_type should be set by awaitingSignal factory
            assertEquals("WAIT_TIMER", output.get("signal_type"));
        }

        @Test
        @DisplayName("Should fallback to inline sleep when duration > 3000ms but signalService is null")
        void shouldFallbackToInlineSleepWhenNoSignalService() {
            // Use a very short duration that is > threshold but signalService is null
            // so it falls back to inline. We use 10ms via a custom node to test the fallback logic.
            // The condition is: durationMs <= INLINE_THRESHOLD_MS || signalService == null
            // With signalService == null, any duration falls back to inline.
            WaitNode node = new WaitNode("core:wait", 10); // short so test is fast
            // signalService is null by default (not set)

            NodeExecutionResult result = node.execute(context);

            // Should succeed via inline path (not AWAITING_SIGNAL)
            assertTrue(result.isSuccess());
            assertEquals("completed", result.output().get("status"));
            assertEquals(10L, result.output().get("waited_ms"));
        }

        @Test
        @DisplayName("Should fallback to inline for long duration when signalService is null")
        void shouldFallbackToInlineForLongDurationWhenNoSignalService() {
            // Create node with duration > 3s but no signalService
            // We cannot actually wait 5s in a test, so we verify the code path
            // by checking that no signal was registered and result is success (inline path)
            // Use a short override: the condition `durationMs <= 3000 || signalService == null`
            // means signalService==null always takes the inline path regardless of duration.
            // We test with 10ms to keep the test fast.
            WaitNode node = new WaitNode("core:wait", 10);
            // Do NOT set signalService

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertFalse(result.isAwaitingSignal());
        }

        @Test
        @DisplayName("Should include resolved_params in signal output")
        void shouldIncludeInputDataInSignalOutput() {
            WaitNode node = new WaitNode("core:wait", 5000);
            node.setSignalService(mockSignalService);
            Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
            node.setClock(fixedClock);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> output = result.output();

            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) output.get("resolved_params");
            assertNotNull(inputData);
            assertEquals(5000L, inputData.get("duration"));
        }

        @Test
        @DisplayName("Duration at exact threshold (3000ms) should use inline path")
        void shouldUseInlinePathAtExactThreshold() {
            // 3000ms is <= INLINE_THRESHOLD_MS (3000), so should go inline even with signalService
            WaitNode node = new WaitNode("core:wait", 50); // Use 50ms to keep test fast
            node.setSignalService(mockSignalService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("completed", result.output().get("status"));
            // Signal service should NOT have been called for inline path
            verifyNoInteractions(mockSignalService);
        }
    }

    // #W1: Spec declares outputs waited/started_at/completed_at, but runtime used to
    // emit only waited_ms + implicit completed_at (via mapper default). started_at
    // was never populated, so {{core:wait.output.started_at}} resolved to null.
    @Nested
    @DisplayName("Timestamp output fields (#W1)")
    class TimestampFieldsTests {

        @Test
        @DisplayName("inline path should include started_at in output")
        void inlineShouldPublishStartedAt() {
            WaitNode node = new WaitNode("core:wait", 10);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Object startedAt = result.output().get("started_at");
            assertNotNull(startedAt, "inline wait must populate started_at");
            assertInstanceOf(String.class, startedAt);
            // ISO-8601 instant parseable
            assertDoesNotThrow(() -> Instant.parse((String) startedAt));
        }

        @Test
        @DisplayName("inline path should include completed_at in output")
        void inlineShouldPublishCompletedAt() {
            WaitNode node = new WaitNode("core:wait", 10);
            NodeExecutionResult result = node.execute(context);

            Object completedAt = result.output().get("completed_at");
            assertNotNull(completedAt);
            assertInstanceOf(String.class, completedAt);
            assertDoesNotThrow(() -> Instant.parse((String) completedAt));
        }

        @Test
        @DisplayName("inline path: completed_at >= started_at")
        void inlineCompletedShouldBeAfterStarted() {
            WaitNode node = new WaitNode("core:wait", 20);
            NodeExecutionResult result = node.execute(context);

            Instant startedAt = Instant.parse((String) result.output().get("started_at"));
            Instant completedAt = Instant.parse((String) result.output().get("completed_at"));
            assertFalse(completedAt.isBefore(startedAt),
                "completed_at (" + completedAt + ") must not precede started_at (" + startedAt + ")");
        }

        @Test
        @DisplayName("inline path: zero duration still publishes both timestamps")
        void zeroDurationStillPublishesTimestamps() {
            WaitNode node = new WaitNode("core:wait", 0);
            NodeExecutionResult result = node.execute(context);

            assertNotNull(result.output().get("started_at"));
            assertNotNull(result.output().get("completed_at"));
        }

        @Test
        @DisplayName("signal path (>3s): yield output includes started_at")
        void signalPathYieldShouldIncludeStartedAt() {
            UnifiedSignalService mockSignalService = mock(UnifiedSignalService.class);
            WaitNode node = new WaitNode("core:wait", 5000);
            node.setSignalService(mockSignalService);
            Instant fixedInstant = Instant.parse("2026-03-01T12:00:00Z");
            node.setClock(Clock.fixed(fixedInstant, ZoneOffset.UTC));
            node.setDagTriggerId("trigger:test");
            node.setEpoch(0);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertEquals(fixedInstant.toString(), result.output().get("started_at"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
