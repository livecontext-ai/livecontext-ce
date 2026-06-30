package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
@DisplayName("StepByStepEventService")
class StepByStepEventServiceTest {

    @Mock
    private WorkflowRedisPublisher redisPublisher;

    private StepByStepEventService service;

    @BeforeEach
    void setUp() {
        service = new StepByStepEventService(redisPublisher);
    }

    @Nested
    @DisplayName("sendReadyStepsEvent()")
    class SendReadyStepsEventTests {

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionNull() {
            service.sendReadyStepsEvent(null, Set.of("mcp:step1"));
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should do nothing when readySteps is null")
        void shouldDoNothingWhenReadyStepsNull() {
            service.sendReadyStepsEvent(mock(WorkflowExecution.class), null);
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish readySteps event via Redis")
        void shouldPublishReadyStepsEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            service.sendReadyStepsEvent(execution, Set.of("mcp:step1", "mcp:step2"));
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("readySteps"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("readySteps", payload.get("type"));
            assertEquals("run-1", payload.get("runId"));
            assertNotNull(payload.get("readySteps"));
            assertNotNull(payload.get("timestamp"));
        }
    }

    @Nested
    @DisplayName("sendPauseEvent()")
    class SendPauseEventTests {

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionNull() {
            service.sendPauseEvent(null, Set.of("mcp:step1"));
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish pause event via Redis")
        void shouldPublishPauseEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            service.sendPauseEvent(execution, Set.of("mcp:step1"));
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("workflowStatus"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("workflowPaused", payload.get("type"));
            assertEquals("PAUSED", payload.get("status"));
        }
    }

    @Nested
    @DisplayName("sendResumeEvent()")
    class SendResumeEventTests {

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionNull() {
            service.sendResumeEvent(null);
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish resume event via Redis")
        void shouldPublishResumeEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            service.sendResumeEvent(execution);
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("workflowStatus"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("workflowResuming", payload.get("type"));
            assertEquals("RESUMING", payload.get("status"));
        }
    }

    @Nested
    @DisplayName("sendRerunEvent()")
    class SendRerunEventTests {

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionNull() {
            service.sendRerunEvent(null, "mcp:step1", Set.of(), 1);
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should do nothing when stepId is null")
        void shouldDoNothingWhenStepIdNull() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            service.sendRerunEvent(execution, null, Set.of(), 1);
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish rerun event via Redis")
        void shouldPublishRerunEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            service.sendRerunEvent(execution, "mcp:step1", Set.of("mcp:step2"), 2);
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("stepRerun"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("stepRerun", payload.get("type"));
            assertEquals("mcp:step1", payload.get("stepId"));
            assertEquals(2, payload.get("epoch"));
        }
    }

    @Nested
    @DisplayName("sendDecisionEvaluatedEvent()")
    class SendDecisionEvaluatedEventTests {

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionNull() {
            service.sendDecisionEvaluatedEvent(null, "core:decision", "if", Set.of(), null);
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish decision evaluated event via Redis")
        void shouldPublishDecisionEvaluatedEvent() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            service.sendDecisionEvaluatedEvent(execution, "core:decision", "if", Set.of("else"), List.of());
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("decisionEvaluated"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("decisionEvaluated", payload.get("type"));
            assertEquals("core:decision", payload.get("coreId"));
            assertEquals("if", payload.get("selectedBranch"));
        }
    }

    @Nested
    @DisplayName("sendWorkflowStatusEvent()")
    class SendWorkflowStatusEventTests {

        @Test
        @DisplayName("Should do nothing when runId is null")
        void shouldDoNothingWhenRunIdNull() {
            service.sendWorkflowStatusEvent(null, RunStatus.COMPLETED, "Done");
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should do nothing when status is null")
        void shouldDoNothingWhenStatusNull() {
            service.sendWorkflowStatusEvent("run-1", null, "Done");
            verifyNoInteractions(redisPublisher);
        }

        @Test
        @DisplayName("Should publish workflow status event via Redis")
        void shouldPublishWorkflowStatusEvent() {
            service.sendWorkflowStatusEvent("run-1", RunStatus.COMPLETED, "Done");
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisPublisher).publishEvent(eq("run-1"), eq("workflowStatus"), payloadCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("workflowStatus", payload.get("type"));
            assertEquals("run-1", payload.get("runId"));
            assertEquals("Done", payload.get("message"));
        }
    }

    @Nested
    @DisplayName("Redis publisher unavailable")
    class RedisPublisherUnavailableTests {

        @Test
        @DisplayName("Should handle null Redis publisher gracefully")
        void shouldHandleNullRedisPublisher() {
            StepByStepEventService serviceWithoutRedis = new StepByStepEventService(null);
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-1");
            // Should not throw
            assertDoesNotThrow(() -> serviceWithoutRedis.sendReadyStepsEvent(execution, Set.of("mcp:step1")));
            assertDoesNotThrow(() -> serviceWithoutRedis.sendPauseEvent(execution, Set.of()));
            assertDoesNotThrow(() -> serviceWithoutRedis.sendResumeEvent(execution));
            assertDoesNotThrow(() -> serviceWithoutRedis.sendRerunEvent(execution, "mcp:step1", Set.of(), 1));
            assertDoesNotThrow(() -> serviceWithoutRedis.sendWorkflowStatusEvent("run-1", RunStatus.COMPLETED, "Done"));
        }
    }
}
