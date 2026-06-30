package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SBS mode parallel epoch rejection and AUTO mode allowance.
 *
 * SBS mode: only one epoch at a time. Run must be WAITING_TRIGGER to accept new fire.
 * AUTO mode: parallel epochs allowed. RUNNING/PAUSED runs accept new fires.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - Epoch Concurrency Policy")
class RunConcurrencyLimitTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private ExecutionQueue executionQueueService;
    @Mock private WorkflowResumeService resumeService;
    @Mock private SnapshotService snapshotService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    private static final String RUN_ID = "run-conc-1";
    private static final String TRIGGER_ID = "trigger:webhook";

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
        ReflectionTestUtils.setField(service, "resumeService", resumeService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private WorkflowRunEntity createRun(RunStatus status, ExecutionMode mode) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "runIdPublic", RUN_ID);
        ReflectionTestUtils.setField(run, "tenantId", "tenant-1");
        run.setStatus(status);
        run.setExecutionMode(mode);
        run.setUpdatedAt(Instant.now());
        run.setMetadata(new HashMap<>());
        // Phase G.1: trigger fire reads plan directly from run entity
        Map<String, Object> minimalPlan = new HashMap<>();
        minimalPlan.put("triggers", List.of(Map.of("type", "manual", "label", "Test")));
        minimalPlan.put("mcps", List.of());
        minimalPlan.put("cores", List.of());
        minimalPlan.put("edges", List.of());
        run.setPlan(minimalPlan);
        return run;
    }

    // ========================================================================
    // SBS mode: auto-close previous epochs before opening new one
    // ========================================================================

    @Nested
    @DisplayName("SBS mode - auto-close previous epochs")
    class SbsRejectionTests {

        @Test
        @DisplayName("Should auto-close previous epochs when SBS run is RUNNING")
        void shouldRejectWhenSbsRunning() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, ExecutionMode.STEP_BY_STEP);

            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID))
                .thenReturn(Set.of(0));
            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // SBS now auto-closes previous epochs and proceeds
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            verify(epochConcurrencyLimiter, atLeastOnce()).release(RUN_ID, TRIGGER_ID);
            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should auto-close previous epochs when SBS run is PAUSED")
        void shouldRejectWhenSbsPaused() {
            WorkflowRunEntity run = createRun(RunStatus.PAUSED, ExecutionMode.STEP_BY_STEP);

            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID))
                .thenReturn(Set.of(0));
            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should allow SBS when run is WAITING_TRIGGER (idle, ready for next epoch)")
        void shouldAllowWhenSbsWaitingTrigger() {
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, ExecutionMode.STEP_BY_STEP);

            // Will proceed past check (and fail later due to missing services - that's OK)
            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Passed the SBS guard → save was called (status set to RUNNING)
            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should allow SBS parallel epoch when forceAutoMode=true")
        void shouldAllowSbsWhenForceAutoMode() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, ExecutionMode.STEP_BY_STEP);

            when(runRepository.save(any())).thenReturn(run);


            // forceAutoMode=true bypasses the SBS guard
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), true);

            verify(runRepository, atLeastOnce()).save(any());
        }
    }

    // ========================================================================
    // AUTO mode: parallel epochs allowed
    // ========================================================================

    @Nested
    @DisplayName("AUTO mode - parallel epochs allowed")
    class AutoModeTests {

        @Test
        @DisplayName("Should allow webhook when AUTO run is RUNNING (parallel epoch)")
        void shouldAllowWhenAutoRunning() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, ExecutionMode.AUTOMATIC);

            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should allow webhook when AUTO run is PAUSED (signal waiting)")
        void shouldAllowWhenAutoPaused() {
            WorkflowRunEntity run = createRun(RunStatus.PAUSED, ExecutionMode.AUTOMATIC);

            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should allow webhook when AUTO run is WAITING_TRIGGER")
        void shouldAllowWhenAutoWaitingTrigger() {
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);

            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(runRepository, atLeastOnce()).save(any());
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should allow when executionMode is null (defaults to AUTOMATIC)")
        void shouldAllowWhenModeNull() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, null);

            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // null mode → guard skipped → proceeds
            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should auto-close SBS epochs for schedule triggers too")
        void shouldRejectSbsForSchedule() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, ExecutionMode.STEP_BY_STEP);

            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, "trigger:schedule"))
                .thenReturn(Set.of(0));
            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, "trigger:schedule", TriggerType.SCHEDULE, Map.of(), false);

            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, "trigger:schedule");
            verify(runRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("Should auto-close SBS epochs for chat triggers too")
        void shouldRejectSbsForChat() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING, ExecutionMode.STEP_BY_STEP);

            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, "trigger:chat"))
                .thenReturn(Set.of(0));
            when(runRepository.save(any())).thenReturn(run);


            service.executeTriggerInternal(run, "trigger:chat", TriggerType.CHAT, Map.of(), false);

            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, "trigger:chat");
            verify(runRepository, atLeastOnce()).save(any());
        }
    }
}
