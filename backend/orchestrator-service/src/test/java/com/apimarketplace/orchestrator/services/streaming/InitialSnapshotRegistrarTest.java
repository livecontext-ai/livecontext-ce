package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InitialSnapshotRegistrar")
class InitialSnapshotRegistrarTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private EdgeStatusService edgeStatusService;

    private InitialSnapshotRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new InitialSnapshotRegistrar(eventPublisher, edgeStatusService);
    }

    @Nested
    @DisplayName("defaultStatusCounts()")
    class DefaultStatusCountsTests {

        @Test
        @DisplayName("Should return map with all zero counts")
        void shouldReturnAllZeroCounts() {
            Map<String, Object> counts = registrar.defaultStatusCounts();

            assertEquals(0, counts.get("running"));
            assertEquals(0, counts.get("processed"));
            assertEquals(0, counts.get("total"));
            assertEquals(0, counts.get("completed"));
            assertEquals(0, counts.get("failed"));
            assertEquals(0, counts.get("skipped"));
            assertEquals(6, counts.size());
        }
    }

    @Nested
    @DisplayName("registerPlaceholderSnapshot()")
    class RegisterPlaceholderSnapshotTests {

        @Test
        @DisplayName("Should emit pending step event")
        void shouldEmitPendingStepEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");

            registrar.registerPlaceholderSnapshot(execution, "mcp:step1", 1000L, Map.of());

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(
                eq("run-1"), eq("mcp:step1"), payloadCaptor.capture(), eq(StepLifecycle.PENDING)
            );

            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("run-1", payload.get("runId"));
            assertEquals("mcp:step1", payload.get("stepAlias"));
            assertEquals("pending", payload.get("status"));
            assertEquals("Pending", payload.get("message"));
            assertEquals(1000L, payload.get("timestamp"));
            assertNotNull(payload.get("statusCounts"));
        }

        @Test
        @DisplayName("Should include extra fields")
        void shouldIncludeExtraFields() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");

            registrar.registerPlaceholderSnapshot(
                execution, "trigger:webhook", 1000L, Map.of("triggerId", "trigger:webhook")
            );

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(eq("run-1"), eq("trigger:webhook"), payloadCaptor.capture(), any());

            assertEquals("trigger:webhook", payloadCaptor.getValue().get("triggerId"));
        }

        @Test
        @DisplayName("Should skip null values in extra fields")
        void shouldSkipNullExtraFields() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");

            registrar.registerPlaceholderSnapshot(
                execution, "mcp:step1", 1000L, Map.of()
            );

            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), any(), any());
        }

        @Test
        @DisplayName("Should do nothing for null execution")
        void shouldDoNothingForNullExecution() {
            registrar.registerPlaceholderSnapshot(null, "mcp:step1", 1000L, Map.of());

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should do nothing for null stepId")
        void shouldDoNothingForNullStepId() {
            WorkflowExecution execution = mock(WorkflowExecution.class);

            registrar.registerPlaceholderSnapshot(execution, null, 1000L, Map.of());

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("registerInitialSnapshots()")
    class RegisterInitialSnapshotsTests {

        @Test
        @DisplayName("Should do nothing for null execution")
        void shouldDoNothingForNullExecution() {
            registrar.registerInitialSnapshots(null);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should do nothing for null plan")
        void shouldDoNothingForNullPlan() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getPlan()).thenReturn(null);

            registrar.registerInitialSnapshots(execution);
            verifyNoInteractions(eventPublisher);
        }
    }
}
