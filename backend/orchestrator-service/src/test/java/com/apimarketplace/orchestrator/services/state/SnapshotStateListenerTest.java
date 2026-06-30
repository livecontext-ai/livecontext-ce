package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotStateListener")
class SnapshotStateListenerTest {

    @Mock
    private StateSnapshotService stateSnapshotService;

    private SnapshotStateListener listener;

    @BeforeEach
    void setUp() {
        listener = new SnapshotStateListener(stateSnapshotService, "run-1");
    }

    @Nested
    @DisplayName("onStateChange()")
    class OnStateChangeTests {

        @Test
        @DisplayName("Should persist READY status to StateSnapshot")
        void shouldPersistReadyStatus() {
            NodeId nodeId = NodeId.step("step1");
            StateChangeEvent event = new StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.READY
            );

            listener.onStateChange(event);

            verify(stateSnapshotService).addReadyNode("run-1", "mcp:step1");
        }

        @Test
        @DisplayName("Should not persist non-READY statuses")
        void shouldNotPersistNonReadyStatuses() {
            NodeId nodeId = NodeId.step("step1");

            // COMPLETED
            listener.onStateChange(new StateChangeEvent(
                "run-1", nodeId, NodeStatus.READY, NodeStatus.COMPLETED
            ));

            // FAILED
            listener.onStateChange(new StateChangeEvent(
                "run-1", nodeId, NodeStatus.RUNNING, NodeStatus.FAILED
            ));

            // SKIPPED
            listener.onStateChange(new StateChangeEvent(
                "run-1", nodeId, NodeStatus.PENDING, NodeStatus.SKIPPED
            ));

            verifyNoInteractions(stateSnapshotService);
        }

        @Test
        @DisplayName("Should ignore events for different runId")
        void shouldIgnoreEventsForDifferentRunId() {
            NodeId nodeId = NodeId.step("step1");
            StateChangeEvent event = new StateChangeEvent(
                "other-run", nodeId, NodeStatus.PENDING, NodeStatus.READY
            );

            listener.onStateChange(event);

            verifyNoInteractions(stateSnapshotService);
        }
    }
}
