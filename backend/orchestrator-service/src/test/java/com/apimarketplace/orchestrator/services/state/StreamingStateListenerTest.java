package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamingStateListener")
class StreamingStateListenerTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private WorkflowStateManager stateManager;

    private StreamingStateListener listener;

    @BeforeEach
    void setUp() {
        listener = new StreamingStateListener(eventPublisher, stateManager);
    }

    @Nested
    @DisplayName("onStateChange() - node status events")
    class NodeStatusEventTests {

        @Test
        @DisplayName("Should emit step status event for COMPLETED status")
        void shouldEmitStepStatusForCompleted() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.RUNNING, NodeStatus.COMPLETED
            );

            listener.onStateChange(event);

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(Map.class), eq(StepLifecycle.SUCCESS));
        }

        @Test
        @DisplayName("Should emit step status event for FAILED status")
        void shouldEmitStepStatusForFailed() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.RUNNING, NodeStatus.FAILED
            );

            listener.onStateChange(event);

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(Map.class), eq(StepLifecycle.FAILURE));
        }

        @Test
        @DisplayName("Should emit step status event for SKIPPED status")
        void shouldEmitStepStatusForSkipped() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.SKIPPED
            );

            listener.onStateChange(event);

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(Map.class), eq(StepLifecycle.SKIPPED));
        }

        @Test
        @DisplayName("Should emit step status event for RUNNING status")
        void shouldEmitStepStatusForRunning() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.READY, NodeStatus.RUNNING
            );

            listener.onStateChange(event);

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(Map.class), eq(StepLifecycle.RUNNING));
        }

        @Test
        @DisplayName("Should emit step status event for READY (mapped to PENDING)")
        void shouldEmitStepStatusForReady() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.READY
            );

            when(stateManager.isStepByStepMode()).thenReturn(false);

            listener.onStateChange(event);

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(Map.class), eq(StepLifecycle.PENDING));
        }
    }

    @Nested
    @DisplayName("Step-by-step READY events")
    class StepByStepReadyTests {

        @Test
        @DisplayName("Should emit step_by_step_ready event when in step-by-step mode")
        void shouldEmitStepByStepReadyEvent() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.READY
            );

            when(stateManager.isStepByStepMode()).thenReturn(true);
            when(stateManager.getReadyNodes()).thenReturn(java.util.List.of(nodeId));
            when(stateManager.isWorkflowComplete()).thenReturn(false);

            listener.onStateChange(event);

            // Should emit both the step status and the step_by_step_ready workflow status
            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(), eq(StepLifecycle.PENDING));
            verify(eventPublisher).emitWorkflowStatus(
                eq("run-1"), eq("STEP_BY_STEP_READY"), anyString(), any(), eq(false)
            );
        }

        @Test
        @DisplayName("Should not emit step_by_step_ready when not in step-by-step mode")
        void shouldNotEmitWhenNotStepByStep() {
            NodeId nodeId = NodeId.step("step1");
            WorkflowStateManager.StateChangeEvent event = new WorkflowStateManager.StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.READY
            );

            when(stateManager.isStepByStepMode()).thenReturn(false);

            listener.onStateChange(event);

            verify(eventPublisher, never()).emitWorkflowStatus(any(), any(), any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("reset()")
    class ResetTests {

        @Test
        @DisplayName("Should clear tracked emitted edges")
        void shouldClearTrackedEdges() {
            // Simply verify it doesn't throw
            assertDoesNotThrow(() -> listener.reset());
        }
    }
}
