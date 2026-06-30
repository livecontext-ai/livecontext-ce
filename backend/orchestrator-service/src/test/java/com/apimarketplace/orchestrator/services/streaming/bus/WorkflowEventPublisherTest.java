package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.WsEventSequencer;
import com.apimarketplace.orchestrator.services.streaming.events.*;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEventPublisher")
class WorkflowEventPublisherTest {

    @Mock
    private WorkflowEventBus bus;

    private WorkflowEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WorkflowEventPublisher(bus, true);
    }

    @Nested
    @DisplayName("emitStep()")
    class EmitStepTests {

        @Test
        @DisplayName("Should publish StepStatusEvent with correct fields")
        void shouldPublishStepStatusEvent() {
            Map<String, Object> payload = Map.of("status", "completed");

            publisher.emitStep("run-1", "mcp:step1", payload, StepLifecycle.SUCCESS);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            WorkflowEvent event = captor.getValue();
            assertInstanceOf(StepStatusEvent.class, event);
            StepStatusEvent stepEvent = (StepStatusEvent) event;
            assertEquals("run-1", stepEvent.runId());
            assertEquals("mcp:step1", stepEvent.normalizedStepId());
            assertEquals(StepLifecycle.SUCCESS, stepEvent.lifecycle());
        }

        @Test
        @DisplayName("Should default lifecycle to RUNNING when null")
        void shouldDefaultLifecycleToRunning() {
            publisher.emitStep("run-1", "mcp:step1", null, null);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            StepStatusEvent stepEvent = (StepStatusEvent) captor.getValue();
            assertEquals(StepLifecycle.RUNNING, stepEvent.lifecycle());
        }

        @Test
        @DisplayName("Should copy payload when copyPayloads is true")
        void shouldCopyPayloadWhenEnabled() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("key", "value");

            publisher.emitStep("run-1", "mcp:step1", payload, StepLifecycle.RUNNING);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            StepStatusEvent stepEvent = (StepStatusEvent) captor.getValue();
            assertNotSame(payload, stepEvent.payload());
            assertEquals("value", stepEvent.payload().get("key"));
        }

        @Test
        @DisplayName("Two-arg emitStep should default to RUNNING lifecycle")
        void twoArgEmitStepShouldDefaultToRunning() {
            publisher.emitStep("run-1", "mcp:step1", null);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            StepStatusEvent stepEvent = (StepStatusEvent) captor.getValue();
            assertEquals(StepLifecycle.RUNNING, stepEvent.lifecycle());
        }
    }

    @Nested
    @DisplayName("emitEdge()")
    class EmitEdgeTests {

        @Test
        @DisplayName("Should publish EdgeStatusEvent with all fields")
        void shouldPublishEdgeStatusEvent() {
            publisher.emitEdge("run-1", "edge-1", "mcp:a", "mcp:b",
                EdgeLifecycle.RUNNING, 3, 1);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            EdgeStatusEvent edgeEvent = (EdgeStatusEvent) captor.getValue();
            assertEquals("run-1", edgeEvent.runId());
            assertEquals("edge-1", edgeEvent.edgeId());
            assertEquals("mcp:a", edgeEvent.from());
            assertEquals("mcp:b", edgeEvent.to());
            assertEquals(EdgeLifecycle.RUNNING, edgeEvent.lifecycle());
            assertEquals(3, edgeEvent.itemIndex());
            assertEquals(1, edgeEvent.iteration());
        }

        @Test
        @DisplayName("Should support edge without itemIndex")
        void shouldSupportEdgeWithoutItemIndex() {
            publisher.emitEdge("run-1", "edge-1", "a", "b", EdgeLifecycle.COMPLETED);

            verify(bus).publish(any(EdgeStatusEvent.class));
        }

        @Test
        @DisplayName("Should support edge with itemIndex only")
        void shouldSupportEdgeWithItemIndexOnly() {
            publisher.emitEdge("run-1", "edge-1", "a", "b", EdgeLifecycle.COMPLETED, 5);

            verify(bus).publish(any(EdgeStatusEvent.class));
        }
    }

    @Nested
    @DisplayName("emitWorkflowStatus()")
    class EmitWorkflowStatusTests {

        @Test
        @DisplayName("Should publish WorkflowStatusEvent")
        void shouldPublishWorkflowStatusEvent() {
            publisher.emitWorkflowStatus("run-1", "COMPLETED", "All done", null, true);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            WorkflowStatusEvent event = (WorkflowStatusEvent) captor.getValue();
            assertEquals("run-1", event.runId());
            assertEquals("COMPLETED", event.status());
            assertEquals("All done", event.message());
            assertTrue(event.terminal());
        }
    }

    @Nested
    @DisplayName("emitWorkflowStatistics()")
    class EmitWorkflowStatisticsTests {

        @Test
        @DisplayName("Should publish WorkflowStatisticsEvent")
        void shouldPublishWorkflowStatisticsEvent() {
            Map<String, Object> payload = Map.of("total", 100);

            publisher.emitWorkflowStatistics("run-1", payload);

            verify(bus).publish(any(WorkflowStatisticsEvent.class));
        }
    }

    @Nested
    @DisplayName("emitLoopEvent()")
    class EmitLoopEventTests {

        @Test
        @DisplayName("Should publish LoopEvent")
        void shouldPublishLoopEvent() {
            Map<String, Object> payload = Map.of("currentIteration", 3);

            publisher.emitLoopEvent("run-1", "core:while", LoopEventType.ITERATION_COMPLETED, payload);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            LoopEvent event = (LoopEvent) captor.getValue();
            assertEquals("core:while", event.loopId());
            assertEquals(LoopEventType.ITERATION_COMPLETED, event.type());
        }
    }

    @Nested
    @DisplayName("emitRetryEvent()")
    class EmitRetryEventTests {

        @Test
        @DisplayName("Should publish RetryEvent")
        void shouldPublishRetryEvent() {
            publisher.emitRetryEvent("run-1", "mcp:step1", 42L, 1, "timeout", null);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            RetryEvent event = (RetryEvent) captor.getValue();
            assertEquals("mcp:step1", event.stepId());
            assertEquals(42L, event.itemId());
            assertEquals(1, event.retryIndex());
            assertEquals("timeout", event.cause());
        }
    }

    @Nested
    @DisplayName("emitDebugLog()")
    class EmitDebugLogTests {

        @Test
        @DisplayName("Should publish DebugLogEvent")
        void shouldPublishDebugLogEvent() {
            publisher.emitDebugLog("run-1", "WARN", "Something happened");

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            DebugLogEvent event = (DebugLogEvent) captor.getValue();
            assertEquals("WARN", event.level());
            assertEquals("Something happened", event.message());
        }
    }

    @Nested
    @DisplayName("emitMergeEvent()")
    class EmitMergeEventTests {

        @Test
        @DisplayName("Should publish MergeEvent")
        void shouldPublishMergeEvent() {
            publisher.emitMergeEvent("run-1", "core:merge1", MergeEventType.MERGED, null);

            verify(bus).publish(any(MergeEvent.class));
        }
    }

    @Nested
    @DisplayName("emitAgentToolCall()")
    class EmitAgentToolCallTests {

        @Test
        @DisplayName("Should publish AgentToolCallEvent")
        void shouldPublishAgentToolCallEvent() {
            Map<String, Object> payload = Map.of("arg1", "val1");
            publisher.emitAgentToolCall("run-1", "agent:myAgent", "get_weather",
                "tc-1", AgentToolCallPhase.CALLING, payload, 0, 2);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            AgentToolCallEvent event = (AgentToolCallEvent) captor.getValue();
            assertEquals("agent:myAgent", event.nodeId());
            assertEquals("get_weather", event.toolName());
            assertEquals(AgentToolCallPhase.CALLING, event.phase());
        }
    }

    @Nested
    @DisplayName("publish()")
    class PublishTests {

        @Test
        @DisplayName("Should not publish null event")
        void shouldNotPublishNullEvent() {
            publisher.publish(null);
            verifyNoInteractions(bus);
        }
    }

    @Nested
    @DisplayName("Phase A1→H regression: statusCounts not refreshed on FE")
    class StatusCountsRegressionAfterPhaseA1Tests {

        @Mock
        private WorkflowRedisPublisher redisPublisher;

        @Mock
        private WsEventSequencer sequencer;

        @Mock
        private SnapshotService snapshotService;

        private WorkflowEventPublisher wired;

        @BeforeEach
        void wirePublisher() {
            wired = new WorkflowEventPublisher(bus, true);
            ReflectionTestUtils.setField(wired, "redisPublisher", redisPublisher);
            ReflectionTestUtils.setField(wired, "wsEventSequencer", sequencer);
            ReflectionTestUtils.setField(wired, "snapshotService", snapshotService);
            lenient().when(sequencer.nextSeq(any())).thenReturn(7L);
        }

        @Test
        @DisplayName("publishSequenced receives camelCase wire type, not PascalCase (StepStatusEvent → stepStatus)")
        void stepStatusEventIsPublishedAsCamelCaseStepStatus() {
            wired.emitStep("run-1", "mcp:s", null, StepLifecycle.SUCCESS);

            ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisPublisher).publishSequenced(eq("run-1"), typeCaptor.capture(),
                    any(StepStatusEvent.class), eq(7L));
            assertEquals("stepStatus", typeCaptor.getValue());
        }

        @Test
        @DisplayName("EdgeStatusEvent → edgeStatus on the wire")
        void edgeStatusEventIsPublishedAsCamelCase() {
            wired.emitEdge("run-1", "e-1", "a", "b", EdgeLifecycle.COMPLETED);

            ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisPublisher).publishSequenced(eq("run-1"), typeCaptor.capture(),
                    any(EdgeStatusEvent.class), anyLong());
            assertEquals("edgeStatus", typeCaptor.getValue());
        }

        @Test
        @DisplayName("WorkflowStatusEvent → workflowStatus on the wire")
        void workflowStatusEventIsPublishedAsCamelCase() {
            wired.emitWorkflowStatus("run-1", "RUNNING", null, null, false);

            ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisPublisher).publishSequenced(eq("run-1"), typeCaptor.capture(),
                    any(WorkflowStatusEvent.class), anyLong());
            assertEquals("workflowStatus", typeCaptor.getValue());
        }

        @Test
        @DisplayName("Every published event triggers snapshotService.markDirty (Phase B.1 invariant)")
        void everyPublishTriggersMarkDirty() {
            wired.emitStep("run-1", "mcp:s", null, StepLifecycle.SUCCESS);
            wired.emitEdge("run-1", "e-1", "a", "b", EdgeLifecycle.COMPLETED);
            wired.emitWorkflowStatus("run-1", "RUNNING", null, null, false);

            verify(snapshotService, times(3)).markDirty("run-1");
        }

        @Test
        @DisplayName("markDirty failure must not break the bus")
        void markDirtyFailureDoesNotBreakBus() {
            doThrow(new RuntimeException("boom")).when(snapshotService).markDirty(any());

            assertDoesNotThrow(() ->
                wired.emitStep("run-1", "mcp:s", null, StepLifecycle.SUCCESS));
            verify(bus).publish(any(StepStatusEvent.class));
        }

        @Test
        @DisplayName("Wire-name table covers every WorkflowEvent record (no missing PascalCase fallback)")
        void wireNameTableCoversAllEvents() {
            assertEquals("stepStatus", WorkflowEventPublisher.wireEventType(StepStatusEvent.class));
            assertEquals("edgeStatus", WorkflowEventPublisher.wireEventType(EdgeStatusEvent.class));
            assertEquals("workflowStatus", WorkflowEventPublisher.wireEventType(WorkflowStatusEvent.class));
            assertEquals("workflowStatistics", WorkflowEventPublisher.wireEventType(WorkflowStatisticsEvent.class));
            assertEquals("loopEvent", WorkflowEventPublisher.wireEventType(LoopEvent.class));
            assertEquals("retryEvent", WorkflowEventPublisher.wireEventType(RetryEvent.class));
            assertEquals("debugLog", WorkflowEventPublisher.wireEventType(DebugLogEvent.class));
            assertEquals("mergeEvent", WorkflowEventPublisher.wireEventType(MergeEvent.class));
            assertEquals("agentToolCall", WorkflowEventPublisher.wireEventType(AgentToolCallEvent.class));
        }
    }

    @Nested
    @DisplayName("Copy payloads disabled")
    class CopyPayloadsDisabledTests {

        @Test
        @DisplayName("Should not copy payload when copyPayloads is false")
        void shouldNotCopyPayload() {
            WorkflowEventPublisher noCopyPublisher = new WorkflowEventPublisher(bus, false);
            Map<String, Object> payload = new HashMap<>();
            payload.put("key", "value");

            noCopyPublisher.emitStep("run-1", "mcp:step1", payload, StepLifecycle.RUNNING);

            ArgumentCaptor<WorkflowEvent> captor = ArgumentCaptor.forClass(WorkflowEvent.class);
            verify(bus).publish(captor.capture());

            StepStatusEvent stepEvent = (StepStatusEvent) captor.getValue();
            assertSame(payload, stepEvent.payload());
        }
    }
}
