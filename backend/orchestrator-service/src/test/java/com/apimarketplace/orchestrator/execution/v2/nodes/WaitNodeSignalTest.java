package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WaitNode (Signal System)")
class WaitNodeSignalTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-04T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock private UnifiedSignalService signalService;
    @Mock private ExecutionContext context;

    @Nested
    @DisplayName("Inline vs Signal threshold")
    class ThresholdTests {

        @Test
        @DisplayName("Should use inline sleep for durations <= 3 seconds")
        void shouldUseInlineForShortWait() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_short")
                .durationMs(2000)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);

            when(context.itemId()).thenReturn("0");
            when(context.itemIndex()).thenReturn(0);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("completed", result.output().get("status"));
            assertEquals(2000L, result.output().get("waited_ms"));

            // Should NOT register a signal for short waits
            verifyNoInteractions(signalService);
        }

        @Test
        @DisplayName("Should use inline sleep for exactly 3 seconds")
        void shouldUseInlineForExactThreshold() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_exact")
                .durationMs(3000)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);

            when(context.itemId()).thenReturn("0");
            when(context.itemIndex()).thenReturn(0);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            verifyNoInteractions(signalService);
        }

        @Test
        @DisplayName("Should register signal and yield for durations > 3 seconds")
        void shouldYieldForLongWait() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_long")
                .durationMs(60_000)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            node.setDagTriggerId("trigger:webhook");
            node.setEpoch(5);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            assertEquals("WAIT_TIMER", result.output().get("signal_type"));

            verify(signalService).registerSignal(
                eq("run-1"), eq("0"), eq("core:wait_long"),
                eq("trigger:webhook"), eq(5),
                eq(SignalType.WAIT_TIMER), any(Map.class), isNull());
        }

        @Test
        @DisplayName("Should fall back to inline when signalService is null")
        void shouldFallBackToInlineWhenNoService() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_noservice")
                .durationMs(60_000) // long wait, but no signal service
                .build();
            // NOT setting signal service

            when(context.itemId()).thenReturn("0");
            when(context.itemIndex()).thenReturn(0);

            NodeExecutionResult result = node.execute(context);

            // Should fall back to inline (blocking) since no signal service
            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Zero duration wait")
    class ZeroDurationTests {

        @Test
        @DisplayName("Should complete immediately for 0ms wait")
        void shouldCompleteImmediatelyForZero() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_zero")
                .durationMs(0)
                .build();
            node.setSignalService(signalService);

            when(context.itemId()).thenReturn("0");
            when(context.itemIndex()).thenReturn(0);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0L, result.output().get("waited_ms"));
            verifyNoInteractions(signalService);
        }
    }

    @Nested
    @DisplayName("awaitingSignal result")
    class AwaitingSignalResultTests {

        @Test
        @DisplayName("Should include metadata in awaiting signal result")
        void shouldIncludeMetadataInResult() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_meta")
                .durationMs(300_000) // 5 min
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertNotNull(result.metadata());
            assertEquals("WAIT_TIMER", result.metadata().get("signal_type"));
        }
    }

    @Nested
    @DisplayName("Split item context (CE-WAIT-002)")
    class SplitItemContextTests {

        @Test
        @DisplayName("regression CE-WAIT-002: a wait inside a split registers its signal WITH the split item context")
        void splitWaitRegistersSignalWithSplitItemData() {
            // SplitAwareNodeExecutor injects the current item into globalData ("item"/"index").
            // The WAIT_TIMER signal must carry it (like UserApprovalNode) so the split context
            // can be rehydrated on resume/restart and per-item timers expose their item.
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_split")
                .durationMs(8_000)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            node.setDagTriggerId("trigger:start");
            node.setEpoch(1);

            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState state =
                mock(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.class);
            when(state.getGlobalData("item")).thenReturn(java.util.Optional.of("Order Beta"));
            when(state.getGlobalData("index")).thenReturn(java.util.Optional.of(1));
            when(context.state()).thenReturn(state);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("1");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            org.mockito.ArgumentCaptor<Map<String, Object>> splitItemData =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(signalService).registerSignal(
                eq("run-1"), eq("1"), eq("core:wait_split"),
                eq("trigger:start"), eq(1),
                eq(SignalType.WAIT_TIMER), any(Map.class), splitItemData.capture());
            assertEquals("Order Beta", splitItemData.getValue().get("current_item"));
            assertEquals(1, splitItemData.getValue().get("current_index"));
        }

        @Test
        @DisplayName("outside a split context the WAIT_TIMER signal keeps a null splitItemData")
        void nonSplitWaitKeepsNullSplitItemData() {
            WaitNode node = WaitNode.builder()
                .nodeId("core:wait_plain")
                .durationMs(8_000)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            node.setDagTriggerId("trigger:start");
            node.setEpoch(1);

            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState state =
                mock(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.class);
            when(state.getGlobalData("item")).thenReturn(java.util.Optional.empty());
            when(context.state()).thenReturn(state);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            node.execute(context);

            verify(signalService).registerSignal(
                eq("run-1"), eq("0"), eq("core:wait_plain"),
                eq("trigger:start"), eq(1),
                eq(SignalType.WAIT_TIMER), any(Map.class), isNull());
        }
    }
}
