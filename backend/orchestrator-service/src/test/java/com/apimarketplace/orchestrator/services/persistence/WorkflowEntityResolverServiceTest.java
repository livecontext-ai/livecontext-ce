package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEntityResolverService")
class WorkflowEntityResolverServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowExecution mockExecution;

    @Mock
    private WorkflowPlan mockPlan;

    private WorkflowEntityResolverService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowEntityResolverService(workflowRepository, workflowRunRepository);
    }

    @Nested
    @DisplayName("resolveWorkflowEntity()")
    class ResolveWorkflowEntityTests {

        @Test
        @DisplayName("Should return empty when planId is null")
        void shouldReturnEmptyWhenPlanIdIsNull() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn(null);

            Optional<WorkflowEntity> result = service.resolveWorkflowEntity(mockExecution);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty when planId is blank")
        void shouldReturnEmptyWhenPlanIdIsBlank() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn("");

            Optional<WorkflowEntity> result = service.resolveWorkflowEntity(mockExecution);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty when planId is not a valid UUID")
        void shouldReturnEmptyWhenPlanIdInvalid() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn("not-a-uuid");

            Optional<WorkflowEntity> result = service.resolveWorkflowEntity(mockExecution);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return cached entity on subsequent calls")
        void shouldReturnCachedEntity() {
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity entity = new WorkflowEntity();
            entity.setId(workflowId);

            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn(workflowId.toString());
            when(mockPlan.getOriginalPlan()).thenReturn(Map.of());
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(entity));

            // First call
            service.resolveWorkflowEntity(mockExecution);

            // Second call should use cache
            service.resolveWorkflowEntity(mockExecution);

            // findById is called each time (because the cache stores the UUID, not the entity)
            verify(workflowRepository, atLeast(1)).findById(workflowId);
        }

        @Test
        @DisplayName("F5: re-queries the existing entity when auto-create races against another replica (DataIntegrityViolationException)")
        void f5ReQueriesOnDataIntegrityViolationDuringAutoCreate() {
            // F5 regression: pre-fix the generic `catch (Exception)` swallowed
            // DataIntegrityViolationException from the unique-constraint race
            // between replicas (both auto-creating the same workflowId), returned
            // null, and the caller poisoned `unresolvedWorkflowRuns` for the
            // entire JVM lifetime. Restart was the only recovery. Post-fix the
            // race is explicitly handled: we re-query the row that the other
            // replica just committed and return it.
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity raceWinner = new WorkflowEntity();
            raceWinner.setId(workflowId);
            raceWinner.setName("Created by other replica");

            when(mockExecution.getRunId()).thenReturn("run-race");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn(workflowId.toString());
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            when(mockPlan.getOriginalPlan()).thenReturn(Map.of("name", "shared-flow"));

            // First findById: returns empty (we don't know about the entity yet);
            // second findById (inside the DIVE catch): returns the row that the
            // racing replica just committed.
            when(workflowRepository.findById(workflowId))
                    .thenReturn(Optional.empty())     // initial lookup before auto-create
                    .thenReturn(Optional.of(raceWinner)); // re-query after DIVE catch
            when(workflowRepository.save(any(WorkflowEntity.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"workflows_pkey\""));

            Optional<WorkflowEntity> result = service.resolveWorkflowEntity(mockExecution);

            assertTrue(result.isPresent(),
                    "F5: race recovery must return the entity from the racing replica's commit, not empty");
            assertEquals(raceWinner.getName(), result.get().getName(),
                    "F5: must return the OTHER replica's entity, not the locally-built one");
        }

        @Test
        @DisplayName("F5: unresolved-cache is TTL-bound - does NOT poison a runId for the JVM lifetime")
        void f5UnresolvedCacheIsTtlBoundedNotJvmLifetime() {
            // F5 regression: prior implementation used `ConcurrentHashMap.newKeySet()`
            // → entries lived until JVM restart. A transient null plan id (which
            // can happen during the first split-second of run setup before the
            // execution context is fully populated) silently blocked the run from
            // ever being persisted again. Post-fix the cache is Caffeine-backed
            // with 60s expireAfterWrite. The cleanup() call must also invalidate
            // (not block) future attempts. This test pins the cleanup contract;
            // the 60s TTL itself is too long to assert in a unit test without
            // clock injection.
            when(mockExecution.getRunId()).thenReturn("run-transient");
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockPlan.getId()).thenReturn(null);

            // First call marks the run as unresolved.
            Optional<WorkflowEntity> first = service.resolveWorkflowEntity(mockExecution);
            assertTrue(first.isEmpty(), "Null plan id → unresolved");

            // Explicit cleanup must invalidate the entry so subsequent calls can retry.
            service.cleanup("run-transient");

            // Now provide a valid plan id (simulating the upstream race resolved).
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity entity = new WorkflowEntity();
            entity.setId(workflowId);
            when(mockPlan.getId()).thenReturn(workflowId.toString());
            when(mockPlan.getOriginalPlan()).thenReturn(Map.of());
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(entity));

            Optional<WorkflowEntity> second = service.resolveWorkflowEntity(mockExecution);
            assertTrue(second.isPresent(),
                    "F5: after cleanup() the runId must be retry-able - no JVM-lifetime poisoning");
        }
    }

    @Nested
    @DisplayName("resolveWorkflowRunId()")
    class ResolveWorkflowRunIdTests {

        @Test
        @DisplayName("Should return execution's workflowRunId if present")
        void shouldReturnExecutionRunId() {
            UUID runUuid = UUID.randomUUID();
            when(mockExecution.getWorkflowRunId()).thenReturn(runUuid);
            when(mockExecution.getRunId()).thenReturn("run-1");

            Optional<UUID> result = service.resolveWorkflowRunId(mockExecution);
            assertTrue(result.isPresent());
            assertEquals(runUuid, result.get());
        }

        @Test
        @DisplayName("Should return empty when no run entity found")
        void shouldReturnEmptyWhenNotFound() {
            when(mockExecution.getWorkflowRunId()).thenReturn(null);
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            Optional<UUID> result = service.resolveWorkflowRunId(mockExecution);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getCurrentEpochFromRun()")
    class GetCurrentEpochTests {

        @Test
        @DisplayName("Should return 0 for null workflowRunId")
        void shouldReturnZeroForNull() {
            assertEquals(0, service.getCurrentEpochFromRun(null));
        }

        @Test
        @DisplayName("Should return 0 when run entity not found")
        void shouldReturnZeroWhenNotFound() {
            UUID runId = UUID.randomUUID();
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());
            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return epoch from metadata")
        void shouldReturnEpochFromMetadata() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(Map.of("currentEpoch", 5));
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(5, service.getCurrentEpochFromRun(runId));
        }

        @Test
        @DisplayName("Should return 0 when metadata has no currentEpoch")
        void shouldReturnZeroWhenNoEpoch() {
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(Map.of());
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(entity));

            assertEquals(0, service.getCurrentEpochFromRun(runId));
        }
    }

    @Nested
    @DisplayName("cacheIds()")
    class CacheIdsTests {

        @Test
        @DisplayName("Should cache workflow and run IDs")
        void shouldCacheIds() {
            UUID workflowId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();

            service.cacheIds("run-1", workflowId, runId);

            // Verify by resolving again (should use cache)
            when(mockExecution.getWorkflowRunId()).thenReturn(null);
            when(mockExecution.getRunId()).thenReturn("run-1");

            // The run ID should be cached
            Optional<UUID> result = service.resolveWorkflowRunId(mockExecution);
            assertTrue(result.isPresent());
            assertEquals(runId, result.get());
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("Should remove all cached data for runId")
        void shouldRemoveCachedData() {
            service.cacheIds("run-1", UUID.randomUUID(), UUID.randomUUID());
            service.cleanup("run-1");

            when(mockExecution.getWorkflowRunId()).thenReturn(null);
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            Optional<UUID> result = service.resolveWorkflowRunId(mockExecution);
            assertTrue(result.isEmpty());
        }
    }
}
