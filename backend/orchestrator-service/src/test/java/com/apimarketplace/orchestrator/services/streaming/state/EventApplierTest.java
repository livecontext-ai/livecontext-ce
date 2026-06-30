package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.events.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventApplier")
class EventApplierTest {

    @Mock
    private RunStateStoreAccessor stateAccessor;

    @Mock
    private RunState mockRunState;

    private EventApplier eventApplier;

    @BeforeEach
    void setUp() {
        eventApplier = new EventApplier(stateAccessor);
    }

    @Nested
    @DisplayName("applyEvent() dispatch")
    class ApplyEventDispatchTests {

        @Test
        @DisplayName("Should dispatch StepStatusEvent to applyStepEvent")
        void shouldDispatchStepStatusEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1",
                Map.of("status", "completed"), StepLifecycle.SUCCESS, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateStep(eq("run-1"), eq("mcp:step1"), any());
        }

        @Test
        @DisplayName("Should dispatch EdgeStatusEvent to applyEdgeEvent")
        void shouldDispatchEdgeStatusEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            EdgeStatusEvent event = new EdgeStatusEvent("run-1", "edge-1", "a", "b",
                EdgeLifecycle.RUNNING, 0, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateEdge("edge-1", "a", "b", EdgeLifecycle.RUNNING, 0, null);
        }

        @Test
        @DisplayName("Should dispatch WorkflowStatusEvent to applyWorkflowStatusEvent")
        void shouldDispatchWorkflowStatusEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            WorkflowStatusEvent event = new WorkflowStatusEvent("run-1", "COMPLETED", "Done",
                Map.of("key", "val"), 100L, true);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateWorkflowStatus(Map.of("key", "val"), "COMPLETED", "Done", true);
        }

        @Test
        @DisplayName("Should dispatch WorkflowStatisticsEvent to applyWorkflowStatisticsEvent")
        void shouldDispatchWorkflowStatisticsEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            Map<String, Object> payload = Map.of("total", 10);
            WorkflowStatisticsEvent event = new WorkflowStatisticsEvent("run-1", payload, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateWorkflowStatistics(payload);
        }

        @Test
        @DisplayName("Should dispatch LoopEvent to applyLoopEvent")
        void shouldDispatchLoopEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            LoopEvent event = new LoopEvent("run-1", "core:while", LoopEventType.STARTED, null, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateLoop("core:while", event);
        }

        @Test
        @DisplayName("Should dispatch RetryEvent to applyRetryEvent")
        void shouldDispatchRetryEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            Map<String, Object> payload = Map.of("reason", "timeout");
            RetryEvent event = new RetryEvent("run-1", "mcp:step1", 1L, 2, "timeout", payload, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateRetry("mcp:step1", 2, payload);
        }

        @Test
        @DisplayName("Should dispatch MergeEvent to applyMergeEvent")
        void shouldDispatchMergeEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            MergeEvent event = new MergeEvent("run-1", "core:merge1", MergeEventType.MERGED, null, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateMerge("core:merge1", event);
        }

        @Test
        @DisplayName("Should dispatch DebugLogEvent to applyDebugLogEvent")
        void shouldDispatchDebugLogEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            DebugLogEvent event = new DebugLogEvent("run-1", "INFO", "Test message", 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).appendLog("INFO", "Test message", 100L);
        }

        @Test
        @DisplayName("Should dispatch AgentToolCallEvent to applyAgentToolCallEvent")
        void shouldDispatchAgentToolCallEvent() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            AgentToolCallEvent event = new AgentToolCallEvent("run-1", "agent:a", "tool",
                "tc-1", AgentToolCallPhase.CALLING, null, 0, null, 100L);

            eventApplier.applyEvent(event);

            verify(mockRunState).updateAgentToolCall(event);
        }
    }

    @Nested
    @DisplayName("applyStepEvent()")
    class ApplyStepEventTests {

        @Test
        @DisplayName("Should sanitize payload before updating state")
        void shouldSanitizePayloadBeforeUpdating() {
            when(stateAccessor.getOrCreateRunState("run-1")).thenReturn(mockRunState);
            Map<String, Object> payload = Map.of("status", "completed", "runId", "run-1");
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1", payload, StepLifecycle.SUCCESS, 100L);

            eventApplier.applyStepEvent(event);

            verify(mockRunState).updateStep(eq("run-1"), eq("mcp:step1"), any(Map.class));
        }
    }
}
