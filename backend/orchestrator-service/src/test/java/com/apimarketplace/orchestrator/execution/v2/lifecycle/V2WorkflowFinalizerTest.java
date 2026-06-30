package com.apimarketplace.orchestrator.execution.v2.lifecycle;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.execution.WorkflowRunFinalizer;
import com.apimarketplace.orchestrator.trigger.WorkflowTriggerDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2WorkflowFinalizer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V2WorkflowFinalizer")
class V2WorkflowFinalizerTest {

    @Mock private V2ExecutionEventService mockEventService;
    @Mock private WorkflowPersistenceService mockPersistenceService;
    @Mock private WorkflowRunFinalizer mockLegacyFinalizer;
    @Mock private WorkflowTriggerDispatchService mockDispatchService;
    @Mock private WorkflowExecution mockExecution;

    private V2WorkflowFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new V2WorkflowFinalizer(
            mockEventService, mockPersistenceService, mockLegacyFinalizer, mockDispatchService
        );
    }

    @Nested
    @DisplayName("finalizeWorkflow")
    class FinalizeWorkflow {

        @Test
        @DisplayName("should return early for null execution")
        void shouldReturnEarlyForNull() {
            WorkflowResult result = WorkflowResult.failed("run-1", "error");
            finalizer.finalizeWorkflow(null, result);

            verifyNoInteractions(mockEventService);
            verifyNoInteractions(mockPersistenceService);
        }

        @Test
        @DisplayName("should finalize successful workflow")
        void shouldFinalizeSuccessful() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getTotalExecutionTime()).thenReturn(5000L);

            ExecutionStatistics stats = new ExecutionStatistics(5, 0, 0, 0, 1, 5000L, RunStatus.RUNNING, 0, 0, Map.of());
            when(mockExecution.getStatistics()).thenReturn(stats);

            WorkflowResult result = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );

            finalizer.finalizeWorkflow(mockExecution, result);

            verify(mockExecution).complete();
            verify(mockLegacyFinalizer).flushAndPersist(mockExecution);
            verify(mockEventService).emitWorkflowComplete(eq(mockExecution), eq(true), anyString());
            verify(mockPersistenceService).recordWorkflowCompletion(mockExecution);
            verify(mockEventService).cleanupExecution("run-1");
            verify(mockDispatchService).dispatchWorkflowCompletion(mockExecution);
        }

        @Test
        @DisplayName("should finalize failed workflow")
        void shouldFinalizeFailed() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getTotalExecutionTime()).thenReturn(2000L);

            ExecutionStatistics stats = new ExecutionStatistics(3, 2, 0, 0, 1, 2000L, RunStatus.RUNNING, 0, 0, Map.of());
            when(mockExecution.getStatistics()).thenReturn(stats);

            WorkflowResult result = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.FAILED,
                1, 0, 1, List.of(), Optional.empty()
            );

            finalizer.finalizeWorkflow(mockExecution, result);

            verify(mockExecution).setStatus(RunStatus.FAILED);
            verify(mockEventService).emitWorkflowComplete(eq(mockExecution), eq(false), anyString());
        }

        @Test
        @DisplayName("should handle exception during finalization")
        void shouldHandleException() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockExecution.getTotalExecutionTime()).thenReturn(0L);

            ExecutionStatistics stats = new ExecutionStatistics(1, 0, 0, 0, 1, 0L, RunStatus.RUNNING, 0, 0, Map.of());
            when(mockExecution.getStatistics()).thenReturn(stats);

            doThrow(new RuntimeException("DB error")).when(mockLegacyFinalizer).flushAndPersist(any());

            WorkflowResult result = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );

            // Should not throw
            finalizer.finalizeWorkflow(mockExecution, result);

            // Should try to emit error event
            verify(mockEventService).emitWorkflowComplete(eq(mockExecution), eq(false), contains("DB error"));
        }
    }

    @Nested
    @DisplayName("finalizeWithError")
    class FinalizeWithError {

        @Test
        @DisplayName("should return early for null execution")
        void shouldReturnEarlyForNull() {
            finalizer.finalizeWithError(null, new RuntimeException("error"));
            verifyNoInteractions(mockEventService);
        }

        @Test
        @DisplayName("should mark execution as failed and emit event")
        void shouldMarkAsFailed() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);

            ExecutionStatistics stats = new ExecutionStatistics(1, 1, 0, 0, 1, 0L, RunStatus.RUNNING, 0, 0, Map.of());
            when(mockExecution.getStatistics()).thenReturn(stats);

            RuntimeException error = new RuntimeException("Something failed");
            finalizer.finalizeWithError(mockExecution, error);

            verify(mockExecution).setStatus(RunStatus.FAILED);
            verify(mockExecution).setError(contains("Something failed"), eq(error));
            verify(mockLegacyFinalizer).flushAndPersist(mockExecution);
            verify(mockEventService).emitWorkflowComplete(eq(mockExecution), eq(false), eq("Something failed"));
            verify(mockPersistenceService).recordWorkflowCompletion(mockExecution);
            verify(mockEventService).cleanupExecution("run-1");
        }

        @Test
        @DisplayName("should handle exception during error finalization")
        void shouldHandleExceptionDuringErrorFinalization() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            when(mockExecution.getStatus()).thenReturn(RunStatus.RUNNING);

            ExecutionStatistics stats = new ExecutionStatistics(0, 0, 0, 0, 0, 0L, RunStatus.RUNNING, 0, 0, Map.of());
            when(mockExecution.getStatistics()).thenReturn(stats);

            doThrow(new RuntimeException("nested error")).when(mockLegacyFinalizer).flushAndPersist(any());

            // Should not throw even if inner operations fail
            finalizer.finalizeWithError(mockExecution, new RuntimeException("original"));
        }
    }
}
