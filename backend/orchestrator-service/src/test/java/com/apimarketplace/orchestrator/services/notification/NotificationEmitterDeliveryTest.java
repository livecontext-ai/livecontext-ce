package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochFailedEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochSucceededEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationCreatedEvent;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two seams between the bell and delivery (V528): a row that was REALLY
 * inserted is handed to delivery, and a PRODUCTION success reports recovery.
 */
@DisplayName("NotificationEmitter - hand-off to notification delivery")
class NotificationEmitterDeliveryTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int PINNED = 7;

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository workflowRunRepository;
    private EntityManager entityManager;
    private Query nativeQuery;
    private ApplicationEventPublisher publisher;
    private NotificationDeliveryService delivery;
    private NotificationEmitter emitter;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        entityManager = mock(EntityManager.class);
        nativeQuery = mock(Query.class);
        publisher = mock(ApplicationEventPublisher.class);
        delivery = mock(NotificationDeliveryService.class);
        emitter = new NotificationEmitter(workflowRepository, workflowRunRepository,
                mock(WorkflowRedisPublisher.class), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(emitter, "entityManager", entityManager);
        emitter.setEventPublisher(publisher);
        emitter.setDeliveryService(delivery);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
    }

    private WorkflowEntity workflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setPinnedVersion(PINNED);
        return wf;
    }

    private WorkflowRunEntity run(Map<String, Object> metadata) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", RUN_ID);
        run.setTenantId("tenant-1");
        run.setRunIdPublic("run_pub");
        run.setEndedAt(Instant.now());
        run.setMetadata(metadata);
        return run;
    }

    @Test
    @DisplayName("An inserted RUN_FAILED row is handed to delivery with its id, workspace and workflow")
    void insertedRowIsPublished() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        emitter.onRunTerminated(new WorkflowRunTerminatedEvent(RUN_ID, WORKFLOW_ID, RunStatus.FAILED, PINNED));

        ArgumentCaptor<NotificationCreatedEvent> captor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().notificationId()).isEqualTo(42L);
        assertThat(captor.getValue().category()).isEqualTo("RUN_FAILED");
        assertThat(captor.getValue().subjectId()).isEqualTo(WORKFLOW_ID);
        assertThat(captor.getValue().runIdPublic()).isEqualTo("run_pub");
    }

    @Test
    @DisplayName("Regression (double send): a row that already existed (ON CONFLICT) is NOT handed to delivery")
    void conflictIsNotPublished() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of());

        emitter.onEpochFailed(new WorkflowEpochFailedEvent(RUN_ID, WORKFLOW_ID, 3, PINNED, "tenant-1",
                "run_pub", Instant.now()));

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("An inserted per-EPOCH failure (schedule, webhook, chat, form) is handed to delivery")
    void insertedEpochFailureIsPublished() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        emitter.onEpochFailed(new WorkflowEpochFailedEvent(RUN_ID, WORKFLOW_ID, 3, PINNED, "tenant-1",
                "run_pub", Instant.now()));

        ArgumentCaptor<NotificationCreatedEvent> captor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().notificationId()).isEqualTo(42L);
        assertThat(captor.getValue().category()).isEqualTo("RUN_FAILED");
        assertThat(captor.getValue().subjectId()).isEqualTo(WORKFLOW_ID);
        assertThat(captor.getValue().runIdPublic()).isEqualTo("run_pub");
    }

    @Test
    @DisplayName("An inserted spending-cap stop is handed to delivery as BUDGET_REACHED")
    void insertedBudgetReachedIsPublished() {
        WorkflowRunEntity run = run(new HashMap<>());
        run.setPlanVersion(PINNED);
        when(workflowRunRepository.findByRunIdPublic("run_pub")).thenReturn(Optional.of(run));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(nativeQuery.getResultList()).thenReturn(List.of(77L));

        emitter.onBudgetReached(new com.apimarketplace.orchestrator.services.events.WorkflowBudgetReachedEvent(
                "run_pub", WORKFLOW_ID, new java.math.BigDecimal("120"), new java.math.BigDecimal("100"), "MONTHLY",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.now()));

        ArgumentCaptor<NotificationCreatedEvent> captor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().notificationId()).isEqualTo(77L);
        assertThat(captor.getValue().category()).isEqualTo("BUDGET_REACHED");
        assertThat(captor.getValue().subjectId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("A production success with an open incident reports recovery")
    void productionSuccessRecovers() {
        when(delivery.hasOpenIncident(WORKFLOW_ID)).thenReturn(true);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(new HashMap<>())));

        emitter.onEpochSucceeded(new WorkflowEpochSucceededEvent(RUN_ID, WORKFLOW_ID, 4, PINNED));

        verify(delivery).onProductionSuccess(WORKFLOW_ID);
    }

    @Test
    @DisplayName("A COMPLETED terminal run reports recovery the same way")
    void completedRunRecovers() {
        when(delivery.hasOpenIncident(WORKFLOW_ID)).thenReturn(true);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(new HashMap<>())));

        emitter.onRunTerminated(new WorkflowRunTerminatedEvent(RUN_ID, WORKFLOW_ID, RunStatus.COMPLETED, PINNED));

        verify(delivery).onProductionSuccess(WORKFLOW_ID);
    }

    @Test
    @DisplayName("No open incident: a success reads nothing else (the common case stays one probe)")
    void noIncidentNoReads() {
        when(delivery.hasOpenIncident(WORKFLOW_ID)).thenReturn(false);

        emitter.onEpochSucceeded(new WorkflowEpochSucceededEvent(RUN_ID, WORKFLOW_ID, 4, PINNED));

        verifyNoInteractions(workflowRepository, workflowRunRepository);
        verify(delivery, never()).onProductionSuccess(any());
    }

    @Test
    @DisplayName("An editor test run that passes does NOT announce that production recovered")
    void editorRunDoesNotRecover() {
        when(delivery.hasOpenIncident(WORKFLOW_ID)).thenReturn(true);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(Map.of("__editorRun__", true))));

        emitter.onEpochSucceeded(new WorkflowEpochSucceededEvent(RUN_ID, WORKFLOW_ID, 4, PINNED));

        verify(delivery, never()).onProductionSuccess(any());
    }

    @Test
    @DisplayName("A success on a version that is not the pinned one does NOT announce recovery")
    void unpinnedVersionDoesNotRecover() {
        when(delivery.hasOpenIncident(WORKFLOW_ID)).thenReturn(true);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow()));

        emitter.onEpochSucceeded(new WorkflowEpochSucceededEvent(RUN_ID, WORKFLOW_ID, 4, PINNED + 1));

        verify(delivery, never()).onProductionSuccess(any());
    }
}
