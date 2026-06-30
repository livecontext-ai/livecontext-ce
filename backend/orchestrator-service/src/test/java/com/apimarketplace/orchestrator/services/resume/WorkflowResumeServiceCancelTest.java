package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowResumeService.cancelWorkflow().
 * Tests the hard-cancel flow: status validation, signal cancellation,
 * terminal CANCELLED status, and cache cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService - cancelWorkflow")
class WorkflowResumeServiceCancelTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;
    @Mock private StateReconstructor stateReconstructor;
    @Mock private RunCacheRegistry cacheRegistry;
    @Mock private ExecutionContextManager contextManager;
    @Mock private StepByStepExecutor stepByStepExecutor;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private UnifiedSignalService unifiedSignalService;

    private WorkflowResumeService service;

    private static final String RUN_ID = "test-run-123";
    private static final String TENANT_ID = "tenant-1";

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowResumeService(
                runRepository, executionService, persistenceService,
                streamingService, runStateStore,
                cacheManager, stateReconstructor, cacheRegistry,
                contextManager, stepByStepExecutor,
                epochManager, stateSnapshotService
        );

        // Inject @Autowired(required = false) field via reflection
        Field signalField = WorkflowResumeService.class.getDeclaredField("unifiedSignalService");
        signalField.setAccessible(true);
        signalField.set(service, unifiedSignalService);
    }

    private WorkflowRunEntity createRunEntity(RunStatus status) {
        WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
        lenient().when(entity.getStatus()).thenReturn(status);
        lenient().when(entity.getMetadata()).thenReturn(new HashMap<>());
        lenient().when(entity.getTenantId()).thenReturn(TENANT_ID);
        return entity;
    }

    @Nested
    @DisplayName("Happy path - hard cancel to CANCELLED")
    class HappyPath {

        @Test
        @DisplayName("Should hard-cancel RUNNING workflow to CANCELLED")
        void shouldCancelRunningWorkflow() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(5);

            service.cancelWorkflow(RUN_ID);

            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runEntity).setEndedAt(any());

            // Verify metadata records cancel info
            ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(runEntity).setMetadata(metadataCaptor.capture());
            Map<String, Object> metadata = metadataCaptor.getValue();
            assertNotNull(metadata.get("cancelledAt"));
            assertEquals("running", metadata.get("cancelledFromStatus"));

            verify(runRepository).save(runEntity);
            // Fix #1 regression: a cancelled run is reactivatable on the SAME runId, so the
            // STREAMING domain (WsEventSequencer's shared seq counter) MUST be preserved -
            // purging it would make the next fire re-seed low -> frontend strict-< drop -> freeze.
            verify(cacheRegistry).cleanupRun(eq(RUN_ID),
                    argThat(domains -> domains.contains(RunScopedCache.CacheDomain.STREAMING)));
        }

        @Test
        @DisplayName("Should hard-cancel PAUSED workflow to CANCELLED")
        void shouldCancelPausedWorkflow() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PAUSED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(3);

            service.cancelWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("Should hard-cancel WAITING_TRIGGER workflow to CANCELLED")
        void shouldCancelWaitingTriggerWorkflow() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(2);

            service.cancelWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runEntity).setEndedAt(any());

            ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(runEntity).setMetadata(metadataCaptor.capture());
            assertEquals("waiting_trigger", metadataCaptor.getValue().get("cancelledFromStatus"));

            verify(runRepository).save(runEntity);
            verify(cacheRegistry).cleanupRun(eq(RUN_ID), anySet());
        }

        @Test
        @DisplayName("Should hard-cancel AWAITING_SIGNAL workflow to CANCELLED (e.g. parked on a User Approval)")
        void shouldCancelAwaitingSignalWorkflow() {
            // Regression: a run parked on a signal (USER_APPROVAL / WAIT_TIMER / WEBHOOK_WAIT) must be
            // cancellable. The old guard only allowed RUNNING/PAUSED/WAITING_TRIGGER, so cancelling a
            // step-by-step run stopped at a User Approval threw "Cannot cancel workflow in status:
            // AWAITING_SIGNAL" (HTTP 400) and the run stayed stuck.
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.AWAITING_SIGNAL);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            // The pending signal wait is torn down, and the run reaches the terminal CANCELLED state.
            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runEntity).setEndedAt(any());

            ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(runEntity).setMetadata(metadataCaptor.capture());
            assertEquals("awaiting_signal", metadataCaptor.getValue().get("cancelledFromStatus"));

            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("Should clear lastCycleResult so display shows CANCELLED (explicit user action)")
        void shouldClearLastCycleResultMetadata() {
            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            lenient().when(runEntity.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            // Simulate a run that completed a previous cycle
            HashMap<String, Object> existingMetadata = new HashMap<>();
            existingMetadata.put("lastCycleResult", "completed");
            existingMetadata.put("lastCycleEpoch", 3);
            existingMetadata.put("lastCycleAt", "2026-03-06T10:00:00Z");
            lenient().when(runEntity.getMetadata()).thenReturn(existingMetadata);
            lenient().when(runEntity.getTenantId()).thenReturn(TENANT_ID);

            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.CANCELLED);

            ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(runEntity).setMetadata(metadataCaptor.capture());
            Map<String, Object> metadata = metadataCaptor.getValue();

            // lastCycleResult cleared - explicit Stop always shows "cancelled"
            // (unlike cancelStaleRuns which preserves it)
            assertNull(metadata.get("lastCycleResult"));
            assertNull(metadata.get("lastCycleEpoch"));
            assertNull(metadata.get("lastCycleAt"));
            // Cancel metadata present
            assertNotNull(metadata.get("cancelledAt"));
            assertEquals("waiting_trigger", metadata.get("cancelledFromStatus"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should no-op when run is already CANCELLED")
        void shouldNoOpWhenAlreadyCancelled() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.CANCELLED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            service.cancelWorkflow(RUN_ID);

            verify(runEntity, never()).setStatus(any());
            verify(runRepository, never()).save(any());
            verify(cacheRegistry, never()).cleanupRun(anyString(), anySet());
        }

        @Test
        @DisplayName("Should cancel signals when UnifiedSignalService is available")
        void shouldCancelSignalsWhenServiceAvailable() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            verify(unifiedSignalService).cancelByRun(RUN_ID);
        }

        @Test
        @DisplayName("Should handle cancel when UnifiedSignalService is null")
        void shouldHandleCancelWhenSignalServiceNull() throws Exception {
            Field signalField = WorkflowResumeService.class.getDeclaredField("unifiedSignalService");
            signalField.setAccessible(true);
            signalField.set(service, null);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));

            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("setAgentCancelSignal MUST run BEFORE cancelByRun and removeByRunId (regression: stopWorkflow ordering race, audit P0 #1, 2026-05-06)")
        void cancelSignalIsSetBeforeOtherCleanupSteps() throws Exception {
            // Race that this guards against: a late async result arriving between
            // cancelByRun and setAgentCancelSignal slips past
            // RunCancellationGuard.isAgentCancelSignalSet (false until the signal is set)
            // and drives successors on a cancelled run. Setting the signal FIRST turns
            // the guard into an immediate reject for any in-flight or late result.
            var redisPublisher = mock(com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
            Field redisField = WorkflowResumeService.class.getDeclaredField("workflowRedisPublisher");
            redisField.setAccessible(true);
            redisField.set(service, redisPublisher);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            // Order assertion is the heart of this test.
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                redisPublisher, unifiedSignalService);
            inOrder.verify(redisPublisher).setAgentCancelSignal(RUN_ID);
            inOrder.verify(unifiedSignalService).cancelByRun(RUN_ID);
        }

        @Test
        @DisplayName("Should continue cancel even if streaming event sending fails")
        void shouldContinueWhenSseEventFails() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            when(runRepository.findByRunIdPublic(RUN_ID))
                    .thenThrow(new RuntimeException("DB failure during streaming"));

            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));

            verify(cacheRegistry).cleanupRun(eq(RUN_ID), anySet());
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("Should throw IllegalArgumentException when run not found")
        void shouldThrowWhenRunNotFound() {
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> service.cancelWorkflow(RUN_ID));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when run is COMPLETED")
        void shouldThrowWhenCompleted() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.cancelWorkflow(RUN_ID));
            assertTrue(ex.getMessage().contains("Cannot cancel workflow"));
            assertTrue(ex.getMessage().contains("COMPLETED"));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when run is FAILED")
        void shouldThrowWhenFailed() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.FAILED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertThrows(IllegalStateException.class,
                    () -> service.cancelWorkflow(RUN_ID));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when run is PENDING")
        void shouldThrowWhenPending() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PENDING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertThrows(IllegalStateException.class,
                    () -> service.cancelWorkflow(RUN_ID));
        }
    }

    @Nested
    @DisplayName("Epoch closure + event publishing on cancel")
    class EpochClosureAndEvent {

        @org.mockito.Mock
        private ApplicationEventPublisher eventPublisher;

        @BeforeEach
        void wireEventPublisher() throws Exception {
            org.mockito.MockitoAnnotations.openMocks(this);
            Field eventField = WorkflowResumeService.class.getDeclaredField("eventPublisher");
            eventField.setAccessible(true);
            eventField.set(service, eventPublisher);
        }

        @Test
        @DisplayName("Cancel publishes WorkflowRunTerminatedEvent with CANCELLED status")
        void shouldPublishTerminatedEventOnCancel() {
            java.util.UUID runUuid = java.util.UUID.randomUUID();
            java.util.UUID workflowUuid = java.util.UUID.randomUUID();

            WorkflowEntity workflow = mock(WorkflowEntity.class);
            lenient().when(workflow.getId()).thenReturn(workflowUuid);
            lenient().when(workflow.getWorkflowType()).thenReturn(WorkflowEntity.WorkflowType.WORKFLOW);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getId()).thenReturn(runUuid);
            lenient().when(runEntity.getWorkflow()).thenReturn(workflow);
            lenient().when(runEntity.getPlanVersion()).thenReturn(3);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);

            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            // Verify the event was published
            ArgumentCaptor<WorkflowRunTerminatedEvent> eventCaptor =
                    ArgumentCaptor.forClass(WorkflowRunTerminatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            WorkflowRunTerminatedEvent event = eventCaptor.getValue();
            assertEquals(runUuid, event.runId());
            assertEquals(workflowUuid, event.workflowId());
            assertEquals(RunStatus.CANCELLED, event.status());
            assertEquals(3, event.planVersion());
        }

        @Test
        @DisplayName("Cancel closes all active epochs in StateSnapshot")
        void shouldCloseEpochsOnCancel() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);

            StateSnapshot snapshot = mock(StateSnapshot.class);
            Map<String, DagState> dags = new HashMap<>();
            dags.put("trigger:webhook", mock(DagState.class));
            dags.put("trigger:schedule", mock(DagState.class));
            when(snapshot.getDags()).thenReturn(dags);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            // Both DAGs' epochs should have been closed
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, "trigger:webhook");
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, "trigger:schedule");
        }

        @Test
        @DisplayName("Cancel succeeds even if epoch closure throws (fail-open)")
        void shouldCancelEvenIfEpochClosureFails() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(stateSnapshotService.getSnapshot(RUN_ID))
                    .thenThrow(new RuntimeException("snapshot corrupted"));

            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));
            verify(runEntity).setStatus(RunStatus.CANCELLED);
        }

        @Test
        @DisplayName("Cancel with null workflow skips event publishing (no NPE)")
        void shouldSkipEventWhenWorkflowIsNull() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getWorkflow()).thenReturn(null);

            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));
            verify(eventPublisher, never()).publishEvent(any(WorkflowRunTerminatedEvent.class));
        }
    }

    /**
     * v3.5 §L3 single-run policy: cancelling a run on an APPLICATION-typed
     * workflow must also suspend the workflow's triggers, because applications
     * are single-run by contract. Cancelling a run on a regular workflow
     * leaves triggers alive (sibling/future runs proceed normally).
     */
    @Nested
    @DisplayName("Single-run policy (v3.5 §L3) - APPLICATION cancel suspends triggers")
    class SingleRunPolicy {

        @org.mockito.Mock
        private com.apimarketplace.trigger.client.TriggerClient triggerClient;

        @BeforeEach
        void wireTriggerClient() throws Exception {
            org.mockito.MockitoAnnotations.openMocks(this);
            Field triggerField = WorkflowResumeService.class.getDeclaredField("triggerClient");
            triggerField.setAccessible(true);
            triggerField.set(service, triggerClient);
        }

        @Test
        @DisplayName("Cancel on APPLICATION-typed workflow suspends its schedule triggers")
        void cancelOnApplicationSuspendsTriggers() {
            java.util.UUID workflowId = java.util.UUID.randomUUID();
            com.apimarketplace.orchestrator.domain.WorkflowEntity wf =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowEntity.class);
            when(wf.getId()).thenReturn(workflowId);
            when(wf.getWorkflowType())
                    .thenReturn(com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType.APPLICATION);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
            lenient().when(runEntity.getWorkflow()).thenReturn(wf);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);
            when(triggerClient.suspendSchedulesByWorkflow(eq(workflowId), anyString(), anyString())).thenReturn(2);

            service.cancelWorkflow(RUN_ID);

            // The single-run policy fired - the workflow's triggers were
            // suspended via the bulk endpoint with USER_DISABLED reason and
            // RUN_TERMINATION source (the dedicated audit-log label for the
            // cancel/pause cascade since 2026-05-13; previously DELETION which
            // conflated with workflow-deletion cascade).
            verify(triggerClient).suspendSchedulesByWorkflow(eq(workflowId), eq("USER_DISABLED"), eq("RUN_TERMINATION"));
        }

        @Test
        @DisplayName("Cancel fails-open (still CANCELS) when the lazy WorkflowEntity.getWorkflowType() throws (CE monolith 'no session')")
        void cancelFailsOpenWhenWorkflowTypeLazyInitThrows() {
            // Regression: in the CE monolith no Hibernate session was bound when the single-run policy
            // touched the lazy WorkflowEntity.getWorkflowType(), throwing LazyInitializationException.
            // It escaped the policy and 500'd the whole cancel ("Failed to cancel workflow: Could not
            // initialize proxy [WorkflowEntity] - no session") - the run could not be cancelled at all.
            com.apimarketplace.orchestrator.domain.WorkflowEntity wf =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowEntity.class);
            when(wf.getWorkflowType()).thenThrow(new org.hibernate.LazyInitializationException("no session"));

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
            lenient().when(runEntity.getWorkflow()).thenReturn(wf);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            // Pre-fix this threw LazyInitializationException (the test would error). Post-fix the
            // best-effort policy swallows it and the run still reaches the terminal CANCELLED state.
            service.cancelWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.CANCELLED);
            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("Cancel on regular WORKFLOW also suspends its triggers (paused-zombie fix, 2026-05-13)")
        void cancelOnWorkflowSuspendsTriggers() {
            // Regression guard for the paused-zombie bug: pre-2026-05-13 a cancel on a
            // WORKFLOW-typed workflow left the schedules at state=ACTIVE while only the
            // run was flipped to CANCELLED. The dispatcher then picked the schedule every
            // minute (matching findDueExecutions on state=ACTIVE) but the resolver
            // returned NO_PRODUCTION_RUN - advanceSchedule was skipped → next_execution_at
            // froze in the past + WARN log every minute forever. The fix makes the
            // suspend cascade symmetric: both WORKFLOW and APPLICATION now route through
            // triggerClient.suspendSchedulesByWorkflow on cancel. Re-arm path is already
            // wired via reactivateWorkflow → reactivateScheduleTriggers → enableSchedulesByWorkflow.
            java.util.UUID workflowId = java.util.UUID.randomUUID();
            com.apimarketplace.orchestrator.domain.WorkflowEntity wf =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowEntity.class);
            when(wf.getId()).thenReturn(workflowId);
            when(wf.getWorkflowType())
                    .thenReturn(com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType.WORKFLOW);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
            lenient().when(runEntity.getWorkflow()).thenReturn(wf);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);
            when(triggerClient.suspendSchedulesByWorkflow(eq(workflowId), anyString(), anyString())).thenReturn(1);

            service.cancelWorkflow(RUN_ID);

            // The trigger-suspend policy fires for WORKFLOW too now, using the same
            // (USER_DISABLED, RUN_TERMINATION) signature as the APPLICATION path so
            // the audit log groups identically. RUN_TERMINATION (added 2026-05-13)
            // is distinct from DELETION (which is for workflow-row deletion cascade).
            verify(triggerClient).suspendSchedulesByWorkflow(eq(workflowId), eq("USER_DISABLED"), eq("RUN_TERMINATION"));
        }

        @Test
        @DisplayName("Cancel succeeds for legacy run with null workflow association (no NPE)")
        void cancelOnRunWithNullWorkflowDoesNotNPE() {
            // Defensive: orphaned/legacy runs may have a null workflow
            // association (FK enforcement gaps in old schemas, manual
            // hot-fixes). The single-run policy must short-circuit, not NPE.
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
            lenient().when(runEntity.getWorkflow()).thenReturn(null);  // <-- null association
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));

            // No trigger suspension attempted because we don't know the
            // workflow type - fall through to legacy "regular workflow"
            // semantics.
            verify(triggerClient, never()).suspendSchedulesByWorkflow(any(), anyString(), anyString());
            verify(runEntity).setStatus(RunStatus.CANCELLED);
        }

        @Test
        @DisplayName("Inside an active tx, the suspend HTTP call defers to afterCommit (no DB connection held during HTTP)")
        void suspendDefersToAfterCommitWhenTxActive() {
            // Phase-5 audit P1: HTTP-in-tx anti-pattern. The fix uses
            // TransactionSynchronizationManager.afterCommit so the row-lock on
            // workflow_runs is released BEFORE the network round-trip. We
            // simulate an active tx by initSynchronization() + flag-check after
            // the test method (the inner Runnable doesn't fire synchronously).
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .initSynchronization();
            try {
                java.util.UUID workflowId = java.util.UUID.randomUUID();
                com.apimarketplace.orchestrator.domain.WorkflowEntity wf =
                        mock(com.apimarketplace.orchestrator.domain.WorkflowEntity.class);
                when(wf.getId()).thenReturn(workflowId);
                when(wf.getWorkflowType())
                        .thenReturn(com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType.APPLICATION);

                WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
                lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
                lenient().when(runEntity.getWorkflow()).thenReturn(wf);
                when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                        .thenReturn(Optional.of(runEntity));
                when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);

                service.cancelWorkflow(RUN_ID);

                // Inside an active tx, the suspend call has been REGISTERED but
                // not yet invoked - afterCommit fires only when the tx commits.
                // The test's "tx" is synthetic so commit never happens; we verify
                // the suspend was deferred (not called yet).
                verify(triggerClient, never()).suspendSchedulesByWorkflow(any(), anyString(), anyString());
                org.assertj.core.api.Assertions.assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .getSynchronizations()).hasSize(1);

                // Manually trigger afterCommit on the registered synchronization
                // to verify the suspend fires when the tx would commit.
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getSynchronizations()
                        .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
                verify(triggerClient).suspendSchedulesByWorkflow(eq(workflowId), eq("USER_DISABLED"), eq("RUN_TERMINATION"));
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .clearSynchronization();
            }
        }

        @Test
        @DisplayName("Trigger-suspension HTTP failure does NOT block the cancel path (fail-open)")
        void cancelSucceedsEvenIfTriggerSuspensionFails() {
            java.util.UUID workflowId = java.util.UUID.randomUUID();
            com.apimarketplace.orchestrator.domain.WorkflowEntity wf =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowEntity.class);
            lenient().when(wf.getId()).thenReturn(workflowId);
            when(wf.getWorkflowType())
                    .thenReturn(com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType.APPLICATION);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            lenient().when(runEntity.getRunIdPublic()).thenReturn(RUN_ID);
            lenient().when(runEntity.getWorkflow()).thenReturn(wf);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            when(cacheRegistry.cleanupRun(eq(RUN_ID), anySet())).thenReturn(1);
            when(triggerClient.suspendSchedulesByWorkflow(eq(workflowId), anyString(), anyString()))
                    .thenThrow(new RuntimeException("trigger-service down"));

            // Even if the suspension call throws, the cancel completes - single-run
            // policy is observability of the contract, not a correctness predicate
            // of run termination.
            assertDoesNotThrow(() -> service.cancelWorkflow(RUN_ID));
            verify(runEntity).setStatus(RunStatus.CANCELLED);
        }

        // Pause path: structurally covered. Both pauseWorkflow:404 and cancelWorkflow:578
        // call the same {@code applyApplicationSingleRunPolicyOnTermination(runEntity,
        // "<caller>")} method, so the {@code RUN_TERMINATION} source emission is
        // exercised by both paths via the cancel tests above. A dedicated pauseWorkflow
        // unit test would require mocking the full state-reconstruction stack
        // (StateReconstructor, cacheManager, contextManager, streamingService) just to
        // reach the suspend cascade - high fixture cost for marginal coverage value.
        // The structural argument is documented in:
        //   - WorkflowResumeService.applyApplicationSingleRunPolicyOnTermination Javadoc
        //   - {@link #cancelOnWorkflowSuspendsTriggers} (the regression guard)
        // If a future refactor splits the pause and cancel cascades, both this comment
        // and the structural coverage will need updating together.
    }
}
