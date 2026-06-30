package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunPersistenceService")
class WorkflowRunPersistenceServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ScheduleSyncService scheduleSyncService;

    private WorkflowRunPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunPersistenceService(workflowRepository, workflowRunRepository, scheduleSyncService);
    }

    @Nested
    @DisplayName("getCurrentEpochFromRun()")
    class GetCurrentEpochFromRunTests {

        @Test
        @DisplayName("Should return 0 for null workflowRunId")
        void shouldReturnZeroForNull() {
            assertEquals(0, service.getCurrentEpochFromRun(null));
        }

        @Test
        @DisplayName("Should return epoch from metadata")
        void shouldReturnEpochFromMetadata() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 3);
            when(entity.getMetadata()).thenReturn(metadata);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(3, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return 0 when entity not found")
        void shouldReturnZeroWhenNotFound() {
            UUID runId = UUID.randomUUID();
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return 0 when metadata is null")
        void shouldReturnZeroWhenMetadataNull() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(null);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return 0 when currentEpoch key missing")
        void shouldReturnZeroWhenKeyMissing() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(Map.of("other", "value"));
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return 0 when currentEpoch is not a Number")
        void shouldReturnZeroWhenNotNumber() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", "not-a-number");
            when(entity.getMetadata()).thenReturn(metadata);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }
    }

    @Nested
    @DisplayName("Cache operations")
    class CacheOperationsTests {

        @Test
        @DisplayName("Should return null for uncached workflow ID")
        void shouldReturnNullForUncachedWorkflowId() {
            assertNull(service.getCachedWorkflowId("run-1"));
        }

        @Test
        @DisplayName("Should return null for uncached workflow run ID")
        void shouldReturnNullForUncachedWorkflowRunId() {
            assertNull(service.getCachedWorkflowRunId("run-1"));
        }

        @Test
        @DisplayName("clearDeduplicationCaches should not throw")
        void shouldClearCachesWithoutError() {
            assertDoesNotThrow(() -> service.clearDeduplicationCaches("run-1"));
        }
    }

    @Nested
    @DisplayName("resolveWorkflowRunId()")
    class ResolveWorkflowRunIdTests {

        @Test
        @DisplayName("Should return existing execution run ID")
        void shouldReturnExistingExecutionRunId() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            UUID runId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(runId);
            when(execution.getRunId()).thenReturn("run-1");

            Optional<UUID> result = service.resolveWorkflowRunId(execution);

            assertTrue(result.isPresent());
            assertEquals(runId, result.get());
        }

        @Test
        @DisplayName("Should resolve from DB when not cached")
        void shouldResolveFromDb() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getWorkflowRunId()).thenReturn(null);
            when(execution.getRunId()).thenReturn("run-1");

            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            UUID dbId = UUID.randomUUID();
            when(runEntity.getId()).thenReturn(dbId);
            when(runEntity.getWorkflow()).thenReturn(null);
            when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            Optional<UUID> result = service.resolveWorkflowRunId(execution);

            assertTrue(result.isPresent());
            assertEquals(dbId, result.get());
            verify(execution).setWorkflowRunId(dbId);
        }

        @Test
        @DisplayName("Should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getWorkflowRunId()).thenReturn(null);
            when(execution.getRunId()).thenReturn("run-unknown");
            when(workflowRunRepository.findByRunIdPublic("run-unknown")).thenReturn(Optional.empty());

            Optional<UUID> result = service.resolveWorkflowRunId(execution);

            assertTrue(result.isEmpty());
        }
    }
}
