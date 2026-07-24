package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.scaling.registry.RedisServiceRegistry;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstanceFailoverService#recoverOrphanedRuns} - the cross-pod failover
 * path that fails runs orphaned by a dead orchestrator instance.
 *
 * <p>Regression (2026-07-21 production-identity audit): this path used to mark the run
 * FAILED via a bare {@code save()} and never publish {@link WorkflowRunTerminatedEvent},
 * unlike the boot-time zombie recovery. When the orphan was the workflow's PRODUCTION run
 * ({@code workflow.production_run_id}), the missing event meant no rearm and no RUN_FAILED
 * notification - the FK kept pointing at a FAILED run and every production lane resolved
 * empty. The event must be published, and it must be published INSIDE the same transaction
 * as the status write (the listeners are AFTER_COMMIT - outside a transaction the event is
 * silently dropped).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InstanceFailoverService - orphaned-run recovery publishes the termination event")
class InstanceFailoverServiceTest {

    private static final String DEAD_INSTANCE = "orch-dead-1";
    private static final String RUN_ID = "run_orphan_1";

    @Mock
    private RedisServiceRegistry registry;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private OrchestratorInstanceRegistrar registrar;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PlatformTransactionManager transactionManager;

    private InstanceFailoverService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-21T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new InstanceFailoverService(registry, redisTemplate, runRepository,
                registrar, clock, eventPublisher, transactionManager);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    private WorkflowRunEntity orphan(RunStatus status) {
        WorkflowEntity workflow = new WorkflowEntity();
        ReflectionTestUtils.setField(workflow, "id", UUID.randomUUID());

        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        run.setPlanVersion(7);
        run.setWorkflow(workflow);
        return run;
    }

    @Test
    @DisplayName("RUNNING orphan → FAILED + WorkflowRunTerminatedEvent published BEFORE the transaction commits")
    void runningOrphanFailedAndEventPublishedInTransaction() {
        WorkflowRunEntity run = orphan(RunStatus.RUNNING);
        String key = RunContextRegistry.activeRunsKeyFor(DEAD_INSTANCE);
        when(setOperations.members(key)).thenReturn(Set.of(RUN_ID));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        service.recoverOrphanedRuns(DEAD_INSTANCE);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getEndedAt()).isEqualTo(clock.instant());
        verify(runRepository).save(run);

        ArgumentCaptor<WorkflowRunTerminatedEvent> captor =
                ArgumentCaptor.forClass(WorkflowRunTerminatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkflowRunTerminatedEvent event = captor.getValue();
        assertThat(event.runId()).isEqualTo(run.getId());
        assertThat(event.workflowId()).isEqualTo(run.getWorkflow().getId());
        assertThat(event.status()).isEqualTo(RunStatus.FAILED);
        assertThat(event.planVersion()).isEqualTo(7);

        // The listeners (RunTerminationListener rearm + NotificationEmitter) are
        // AFTER_COMMIT: the event only reaches them if it is published inside the
        // transaction, i.e. after getTransaction and before commit.
        InOrder inTx = inOrder(transactionManager, eventPublisher);
        inTx.verify(transactionManager).getTransaction(any());
        inTx.verify(eventPublisher).publishEvent(any(WorkflowRunTerminatedEvent.class));
        inTx.verify(transactionManager).commit(any());

        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("Orphan not in RUNNING (already parked WAITING_TRIGGER) → untouched, no event")
    void nonRunningOrphanLeftUntouched() {
        WorkflowRunEntity run = orphan(RunStatus.WAITING_TRIGGER);
        String key = RunContextRegistry.activeRunsKeyFor(DEAD_INSTANCE);
        when(setOperations.members(key)).thenReturn(Set.of(RUN_ID));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        service.recoverOrphanedRuns(DEAD_INSTANCE);

        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("Orphaned run row missing → skipped without event, tracking key still cleaned")
    void missingRunRowSkipped() {
        String key = RunContextRegistry.activeRunsKeyFor(DEAD_INSTANCE);
        when(setOperations.members(key)).thenReturn(Set.of(RUN_ID));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        service.recoverOrphanedRuns(DEAD_INSTANCE);

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(redisTemplate).delete(key);
    }
}
