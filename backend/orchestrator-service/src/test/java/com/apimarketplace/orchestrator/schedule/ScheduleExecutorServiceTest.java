package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExecutorService")
class ScheduleExecutorServiceTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private com.apimarketplace.agent.client.AgentClient agentClient;
    @Mock private com.apimarketplace.conversation.client.ConversationClient conversationServiceClient;

    private ScheduleExecutorService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:daily_9am";
    private static final String RUN_ID = "run-schedule-123";

    @BeforeEach
    void setUp() throws Exception {
        service = new ScheduleExecutorService(
                triggerClient, workflowRepository, runRepository, triggerService, productionRunResolver,
                agentClient, conversationServiceClient, null);
        // Same-thread dispatcher: tests assert post-conditions synchronously.
        // Production uses spreadScheduler.schedule(...) with calculated delay.
        service.setSpreadDispatcherForTesting((task, delayMs) -> task.run());
        // Disable heap-pressure backpressure for the default test fixture by raising
        // the threshold above any achievable heap utilisation. Tests that exercise
        // backpressure explicitly use a Mockito spy on isHeapUnderPressure(). Without
        // this, the test JVM's natural heap utilisation (often >85% under mvn surefire
        // defaults) would trigger the whole-tick skip and break unrelated tests.
        java.lang.reflect.Field thresholdField = ScheduleExecutorService.class
            .getDeclaredField("heapPressureThreshold");
        thresholdField.setAccessible(true);
        thresholdField.setDouble(service, 2.0);

        // ProductionRunResolver delegates to existing repo stubs (refactor compat).
        // Stub the policy-aware resolve(workflowId, policy). The stub mimics the
        // resolver's actual behavior given the chosen policy:
        //   - LATEST_WAITING_TRIGGER → query by (workflow_id, plan_version, status=WAITING_TRIGGER)
        //   - LATEST_TRUSTED         → query by (workflow_id, plan_version, status IN trusted)
        // Pre-existing tests with workflow.pinnedVersion=null relied on the pre-strict-pin
        // fallback that returned the latest run regardless of version. The current resolver
        // returns NOT_PINNED in that case - but the legacy stub keeps the old fallback
        // for backward-compat with tests written before strict-pin landed (they cover
        // accumulation/concurrency/payload behavior independent of pin strictness).
        lenient().when(productionRunResolver.resolve(any(), any())).thenAnswer(inv -> {
            java.util.UUID wfId = inv.getArgument(0);
            var policy = (com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy)
                    inv.getArgument(1);
            var wf = workflowRepository.findById(wfId).orElse(null);
            if (wf == null) {
                return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                    java.util.Optional.empty(),
                    com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.WORKFLOW_MISSING,
                    null);
            }
            Integer pinned = wf.getPinnedVersion();
            // Legacy fallback for tests written before strict-pin enforcement: when
            // workflow.pinnedVersion is null, use the unfiltered findFirst...
            // This mimics what the resolver did pre-da79b9056.
            java.util.Optional<com.apimarketplace.orchestrator.domain.WorkflowRunEntity> r;
            if (pinned == null) {
                r = runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId);
                var outcome = r.isPresent()
                    ? com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND
                    : com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NOT_PINNED;
                return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                    r, outcome, wf.getName());
            }
            r = switch (policy) {
                case LATEST_WAITING_TRIGGER -> runRepository
                    .findFirstByWorkflowIdAndPlanVersionAndStatusOrderByStartedAtDesc(
                        wfId, pinned, com.apimarketplace.orchestrator.domain.workflow.RunStatus.WAITING_TRIGGER);
                case LATEST_TRUSTED, BY_PRODUCTION_RUN_ID -> runRepository
                    .findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(wfId, pinned);
            };
            // Fallback to the unfiltered query if the strict path returned empty -
            // older tests stub only the unfiltered method.
            if (r.isEmpty()) {
                r = runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId);
            }
            var outcome = r.isPresent()
                ? com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND
                : com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NO_PRODUCTION_RUN;
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                r, outcome, wf.getName());
        });
    }

    private ScheduledExecutionDto createSchedule() {
        return createSchedule(SCHEDULE_ID);
    }

    private ScheduledExecutionDto createSchedule(UUID scheduleId) {
        ScheduledExecutionDto schedule = new ScheduledExecutionDto();
        schedule.setId(scheduleId);
        schedule.setWorkflowId(WORKFLOW_ID);
        schedule.setTriggerId(TRIGGER_ID);
        schedule.setTenantId("tenant-1");
        schedule.setCronExpression("0 9 * * *");
        schedule.setTimezone("UTC");
        schedule.setEnabled(true);
        return schedule;
    }

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setPinnedVersion(pinnedVersion);
        return workflow;
    }

    private WorkflowRunEntity createRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        return run;
    }

    private WorkflowRunEntity createRun(RunStatus status, Integer planVersion) {
        WorkflowRunEntity run = createRun(status);
        run.setPlanVersion(planVersion);
        return run;
    }

    private TriggerExecutionResult successResult() {
        return TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE, Set.of(), 1);
    }

    private TriggerExecutionResult failureResult(String msg) {
        return TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE, msg);
    }

    /**
     * Invoke the private prepareScheduleExecution method via reflection.
     */
    private ScheduleExecutorService.ScheduleRunInfo invokePrepare(ScheduledExecutionDto schedule) {
        try {
            Method method = ScheduleExecutorService.class.getDeclaredMethod(
                    "prepareScheduleExecution", ScheduledExecutionDto.class);
            method.setAccessible(true);
            return (ScheduleExecutorService.ScheduleRunInfo) method.invoke(service, schedule);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke prepareScheduleExecution", e);
        }
    }

    /**
     * Stub a complete happy-path scenario for a schedule.
     * Uses ProductionRunResolver (centralized) instead of the old direct repository pattern.
     * Defaults to a pinned version 5 since strict-pin enforcement is now the default.
     */
    private WorkflowRunEntity stubHappyPath() {
        WorkflowEntity workflow = createWorkflow(5);
        WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, 5);
        when(productionRunResolver.resolve(eq(WORKFLOW_ID),
                eq(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER)))
                .thenReturn(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                    Optional.of(run),
                    com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                    "test-workflow"));
        when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                .thenReturn(successResult());
        return run;
    }

    // ==================== Production Pin Enforcement (centralized) ====================

    @Nested
    @DisplayName("prepareScheduleExecution() - Production pin enforcement")
    class ProductionPinTests {

        private com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution found(WorkflowRunEntity run) {
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                Optional.of(run),
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                "test-workflow");
        }

        private com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution outcome(
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome o) {
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                Optional.empty(), o, "test-workflow");
        }

        private static final com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy WAITING =
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER;

        @Test
        @DisplayName("Pinned + WAITING_TRIGGER run found: schedule fires (centralized via resolver)")
        void pinnedFiresOnResolvedRun() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, 5);

            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING)).thenReturn(found(run));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNotNull();
            assertThat(info.run()).isEqualTo(run);
            verify(productionRunResolver).resolve(WORKFLOW_ID, WAITING);
            // The dispatch service no longer talks to runRepository directly
            verify(runRepository, never()).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(any(), any());
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
        }

        @Test
        @DisplayName("Unpinned: schedule is SKIPPED (no fire) and trigger NOT disabled")
        void unpinnedSkipsButKeepsScheduleEnabled() {
            ScheduledExecutionDto schedule = createSchedule();
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NOT_PINNED));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(triggerClient, never()).disableSchedule(any());
        }

        @Test
        @DisplayName("Pinned but no WAITING_TRIGGER run → SKIP this tick, do NOT disable (round-7 fix)")
        void noProductionRunSkipsButDoesNotDisable() {
            ScheduledExecutionDto schedule = createSchedule();
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NO_PRODUCTION_RUN));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            // Round-7: no auto-disable from dispatch - the trigger lifecycle is owned by
            // pin/admin/reaper, not by the dispatch hot path.
            verify(triggerClient, never()).disableSchedule(any());
        }

        @Test
        @DisplayName("Workflow missing → SKIP this tick, do NOT disable (round-7 fix)")
        void workflowMissingSkipsButDoesNotDisable() {
            ScheduledExecutionDto schedule = createSchedule();
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.WORKFLOW_MISSING));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(triggerClient, never()).disableSchedule(any());
        }
    }

    // ==================== Non-WAITING_TRIGGER Run Defensive Skip ====================
    //
    // Round-7 redesign: the resolver now uses LATEST_WAITING_TRIGGER policy, so it
    // pre-filters to WAITING_TRIGGER runs only. Dispatch keeps a defensive guard:
    // if a non-WAITING_TRIGGER run somehow surfaces (race between resolver SELECT
    // and dispatch), skip this tick - never auto-disable the schedule.

    @Nested
    @DisplayName("prepareScheduleExecution() - Defensive non-WAITING_TRIGGER skip")
    class DefensiveSkipTests {

        private static final com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy WAITING =
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER;

        private com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution found(WorkflowRunEntity run) {
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                Optional.of(run),
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                "test-workflow");
        }

        @Test
        @DisplayName("CANCELLED run leaks past resolver → SKIP this tick, do NOT disable (round-7 fix)")
        void cancelledRunSkipsButDoesNotDisable() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = createRun(RunStatus.CANCELLED, 5);
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING)).thenReturn(found(run));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(triggerClient, never()).disableSchedule(any());
        }

        @Test
        @DisplayName("TIMEOUT run leaks past resolver → SKIP this tick, do NOT disable")
        void timeoutRunSkipsButDoesNotDisable() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = createRun(RunStatus.TIMEOUT, 5);
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING)).thenReturn(found(run));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(triggerClient, never()).disableSchedule(any());
        }

        @Test
        @DisplayName("COMPLETED run leaks past resolver → SKIP this tick (LATEST_WAITING_TRIGGER policy is strict)")
        void completedRunSkipsButDoesNotDisable() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = createRun(RunStatus.COMPLETED, 5);
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING)).thenReturn(found(run));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(triggerClient, never()).disableSchedule(any());
        }
    }

    // ==================== SBS schedule fallback ====================
    //
    // Bug: a scheduled workflow in STEP_BY_STEP mode never got a new epoch. The
    // strict LATEST_WAITING_TRIGGER policy finds only WAITING_TRIGGER runs, but a
    // step-by-step run rests in PAUSED/RUNNING/AWAITING_SIGNAL while the user steps
    // (reconcileSbsRunStatus parks it there), so every scheduled tick was silently
    // skipped. Fix: when the primary resolution is NO_PRODUCTION_RUN, fall back to
    // resolveStepByStepRun() and fire the SBS run, creating a fresh epoch this tick
    // (executeTriggerInternal's SBS branch closes the open epoch + opens a new one).

    @Nested
    @DisplayName("prepareScheduleExecution() - SBS fallback (scheduled step-by-step run)")
    class SbsFallbackTests {

        private static final com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy WAITING =
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER;

        private com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution outcome(
                com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome o) {
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                Optional.empty(), o, "test-workflow");
        }

        private WorkflowRunEntity sbsRun(RunStatus status) {
            WorkflowRunEntity run = createRun(status, 5);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            return run;
        }

        @Test
        @DisplayName("No WAITING_TRIGGER run but a PAUSED step-by-step run exists → FIRE it (new epoch this tick)")
        void sbsPausedRunIsFired() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity sbs = sbsRun(RunStatus.PAUSED);
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NO_PRODUCTION_RUN));
            when(productionRunResolver.resolveStepByStepRun(WORKFLOW_ID))
                .thenReturn(Optional.of(sbs));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNotNull();
            assertThat(info.run()).isEqualTo(sbs);
            assertThat(info.triggerId()).isEqualTo(TRIGGER_ID);
        }

        @Test
        @DisplayName("No WAITING_TRIGGER run and no step-by-step run → SKIP (automatic PAUSED keeps strict WAITING_TRIGGER)")
        void noSbsRunSkips() {
            ScheduledExecutionDto schedule = createSchedule();
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NO_PRODUCTION_RUN));
            when(productionRunResolver.resolveStepByStepRun(WORKFLOW_ID))
                .thenReturn(Optional.empty());

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
        }

        @Test
        @DisplayName("NOT_PINNED → SBS fallback is NOT attempted (only NO_PRODUCTION_RUN triggers it)")
        void notPinnedDoesNotTrySbsFallback() {
            ScheduledExecutionDto schedule = createSchedule();
            when(productionRunResolver.resolve(WORKFLOW_ID, WAITING))
                .thenReturn(outcome(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NOT_PINNED));

            ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule);

            assertThat(info).isNull();
            verify(productionRunResolver, never()).resolveStepByStepRun(any());
        }
    }

    // ==================== executeNow() ====================

    @Nested
    @DisplayName("executeNow()")
    class ExecuteNowTests {

        @Test
        @DisplayName("Happy path: finds run and executes")
        void executeNowHappyPath() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = stubHappyPath();

            TriggerExecutionResult result = service.executeNow(schedule);

            assertThat(result.success()).isTrue();
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
        }

        @Test
        @DisplayName("No active run → failure result")
        void executeNowNoRun() {
            ScheduledExecutionDto schedule = createSchedule();

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            TriggerExecutionResult result = service.executeNow(schedule);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No active run found");
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("executeNow still calls recordScheduleExecution (optimistic advance) before execution")
        void shouldAdvanceBeforeExecution() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = stubHappyPath();

            service.executeNow(schedule);

            // executeNow uses the optimistic advance path (recordScheduleExecution before executeTrigger)
            InOrder inOrder = inOrder(triggerClient, triggerService);
            inOrder.verify(triggerClient).recordScheduleExecution(SCHEDULE_ID);
            inOrder.verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
        }

        @Test
        @DisplayName("executeNow: if advanceSchedule HTTP call fails, execution still proceeds")
        void executeNowProceedsWhenAdvanceFails() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = stubHappyPath();

            doThrow(new RuntimeException("HTTP 503 trigger-service down"))
                    .when(triggerClient).recordScheduleExecution(SCHEDULE_ID);

            TriggerExecutionResult result = service.executeNow(schedule);

            // Even though advance failed, execution still happened
            assertThat(result.success()).isTrue();
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
        }
    }

    // ==================== executeSchedule() full flow ====================

    @Nested
    @DisplayName("executeSchedule() - Full Flow")
    class ExecuteScheduleFullFlowTests {

        @Test
        @DisplayName("Happy path: prepare → execute → log (advance already happened in claimAndAdvanceDueSchedules)")
        void fullFlowHappyPath() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = stubHappyPath();

            service.executeSchedule(schedule);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
            // Workflow schedules are advanced atomically by claimAndAdvanceDueSchedules at the trigger-service level.
            // The orchestrator no longer calls recordScheduleExecution for workflow schedules.
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("Prepare returns null → skip advance and execute")
        void prepareReturnsNullSkipsExecution() {
            ScheduledExecutionDto schedule = createSchedule();

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            service.executeSchedule(schedule);

            verifyNoInteractions(triggerService);
            // recordScheduleExecution should NOT be called when prepare returns null
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("Queue unavailable skips advance so cron fire remains due")
        void queueUnavailableSkipsAdvanceAndExecution() {
            ScheduledExecutionDto schedule = createSchedule();
            stubHappyPath();
            ExecutionQueue executionQueue = mock(ExecutionQueue.class);
            when(executionQueue.isReadyForEnqueue()).thenReturn(false);
            service.setExecutionQueueForTesting(executionQueue);

            service.executeSchedule(schedule);

            verify(triggerClient, never()).recordScheduleExecution(any());
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Queue enqueue failure: workflow schedule was already advanced by claimAndAdvanceDueSchedules, no redundant advance")
        void queueFailureNoRedundantAdvance() {
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setNextExecutionAt(Instant.parse("2026-05-22T09:00:00Z"));
            schedule.setLastExecutionAt(Instant.parse("2026-05-21T09:00:00Z"));
            schedule.setExecutionCount(4);
            WorkflowRunEntity run = stubHappyPath();
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                    .thenReturn(TriggerExecutionResult.failure(
                            RUN_ID,
                            TRIGGER_ID,
                            TriggerType.SCHEDULE,
                            com.apimarketplace.orchestrator.trigger.queue.RedisExecutionQueueService.QUEUE_UNAVAILABLE_MESSAGE));

            service.executeSchedule(schedule);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
            // Workflow schedules no longer call recordScheduleExecution (advance is pre-execution via claimAndAdvanceDueSchedules)
            verify(triggerClient, never()).recordScheduleExecution(any());
        }
    }

    // ==================== Queue Acceptance Advance Safety ====================

    @Nested
    @DisplayName("Pre-execution advance - workflow schedules advanced by claimAndAdvanceDueSchedules, not by orchestrator")
    class PreExecutionAdvanceTests {

        @Test
        @DisplayName("Workflow schedule: recordScheduleExecution is NOT called (advance is pre-execution via claimAndAdvanceDueSchedules)")
        void workflowScheduleDoesNotCallRecordScheduleExecution() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowRunEntity run = stubHappyPath();

            service.executeSchedule(schedule);

            // Advance happened atomically in claimAndAdvanceDueSchedules at the trigger-service level.
            // The orchestrator only calls executeTrigger.
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("If executeTrigger throws, no separate advance call is needed (already advanced pre-execution)")
        void noAdvanceNeededWhenExecutionThrows() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Simulated execution failure"));

            try {
                service.executeSchedule(schedule);
            } catch (RuntimeException e) {
                // expected
            }

            // Schedule was already advanced by claimAndAdvanceDueSchedules - no recordScheduleExecution call
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("If execution fails (returns failure result), no separate advance needed (already pre-advanced)")
        void noAdvanceNeededOnFailedExecution() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(failureResult("Workflow execution failed"));

            service.executeSchedule(schedule);

            // Advance was pre-execution - no recordScheduleExecution call from orchestrator
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("Prepare returns null → no advance and no execution")
        void doNotAdvanceWhenPrepareReturnsNull() {
            ScheduledExecutionDto schedule = createSchedule();

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            service.executeSchedule(schedule);

            verify(triggerClient, never()).recordScheduleExecution(any());
            verifyNoInteractions(triggerService);
        }
    }

    // ==================== In-Memory Concurrency Guard ====================

    @Nested
    @DisplayName("In-Memory Concurrency Guard")
    class ConcurrencyGuardTests {

        @Test
        @DisplayName("Daemon should skip schedule already being executed by previous tick")
        void shouldSkipAlreadyExecutingSchedule() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            // Make executeTrigger block until we release the latch
            CountDownLatch executionStarted = new CountDownLatch(1);
            CountDownLatch executionCanFinish = new CountDownLatch(1);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any())).thenAnswer(invocation -> {
                executionStarted.countDown();
                executionCanFinish.await(5, TimeUnit.SECONDS);
                return successResult();
            });

            // Simulate tick 1 in a background thread
            Thread tick1 = new Thread(() -> {
                try {
                    service.checkAndExecuteSchedules();
                } catch (Exception e) {
                    // ignore
                }
            });

            // Prepare due schedules for both ticks
            when(triggerClient.claimAndAdvanceDueSchedules(any()))
                    .thenReturn(List.of(schedule));

            tick1.start();

            try {
                // Wait for tick 1 to start executing
                assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // Now the schedule is in executingScheduleIds
                assertThat(service.isScheduleExecuting(SCHEDULE_ID)).isTrue();

                // Simulate tick 2 - same schedule should be skipped
                AtomicInteger tick2TriggerCalls = new AtomicInteger(0);
                // Reset the invocation count for triggerService
                // tick 2 shouldn't invoke executeTrigger again
                service.checkAndExecuteSchedules();

                // executeTrigger should have been called only ONCE (from tick 1)
                verify(triggerService, times(1)).executeTrigger(any(), any(), any(), any());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executionCanFinish.countDown();
                try {
                    tick1.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Test
        @DisplayName("Guard is released after successful execution")
        void guardReleasedAfterSuccess() {
            ScheduledExecutionDto schedule = createSchedule();
            stubHappyPath();

            when(triggerClient.claimAndAdvanceDueSchedules(any()))
                    .thenReturn(List.of(schedule));

            service.checkAndExecuteSchedules();

            // After successful execution, guard should be released
            assertThat(service.isScheduleExecuting(SCHEDULE_ID)).isFalse();
        }

        @Test
        @DisplayName("Guard is released even when execution throws")
        void guardReleasedAfterException() {
            ScheduledExecutionDto schedule = createSchedule();
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Simulated crash"));
            when(triggerClient.claimAndAdvanceDueSchedules(any()))
                    .thenReturn(List.of(schedule));

            service.checkAndExecuteSchedules();

            // Guard must be released even after exception (finally block)
            assertThat(service.isScheduleExecuting(SCHEDULE_ID)).isFalse();
        }

        @Test
        @DisplayName("Different schedule IDs can execute concurrently")
        void differentSchedulesCanRunConcurrently() {
            UUID scheduleId2 = UUID.randomUUID();
            UUID workflowId2 = UUID.randomUUID();

            ScheduledExecutionDto schedule1 = createSchedule();
            ScheduledExecutionDto schedule2 = createSchedule(scheduleId2);
            schedule2.setWorkflowId(workflowId2);

            WorkflowEntity workflow1 = createWorkflow(null);
            WorkflowEntity workflow2 = new WorkflowEntity();
            workflow2.setId(workflowId2);

            WorkflowRunEntity run1 = createRun(RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity run2 = new WorkflowRunEntity();
            run2.setRunIdPublic("run-2");
            run2.setStatus(RunStatus.WAITING_TRIGGER);
            run2.setExecutionMode(ExecutionMode.AUTOMATIC);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow1));
            when(workflowRepository.findById(workflowId2)).thenReturn(Optional.of(workflow2));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run1));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId2))
                    .thenReturn(Optional.of(run2));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(successResult());
            when(triggerClient.claimAndAdvanceDueSchedules(any()))
                    .thenReturn(List.of(schedule1, schedule2));

            service.checkAndExecuteSchedules();

            // Both schedules should have been executed
            verify(triggerService, times(2)).executeTrigger(any(), any(), any(), any());
            // Workflow schedules are advanced pre-execution by claimAndAdvanceDueSchedules - no recordScheduleExecution
            verify(triggerClient, never()).recordScheduleExecution(any());
        }
    }

    // ==================== checkAndExecuteSchedules() Daemon ====================

    @Nested
    @DisplayName("checkAndExecuteSchedules() - Daemon Behavior")
    class DaemonBehaviorTests {

        @Test
        @DisplayName("No due schedules → no execution, no interaction with triggerService")
        void noDueSchedules() {
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(Collections.emptyList());

            service.checkAndExecuteSchedules();

            verifyNoInteractions(triggerService);
            verifyNoInteractions(workflowRepository);
        }

        @Test
        @DisplayName("Null due schedules → no execution")
        void nullDueSchedules() {
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(null);

            service.checkAndExecuteSchedules();

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Multiple due schedules → all executed (advance already happened in claimAndAdvanceDueSchedules)")
        void multipleDueSchedules() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();

            ScheduledExecutionDto s1 = createSchedule(id1);
            ScheduledExecutionDto s2 = createSchedule(id2);
            ScheduledExecutionDto s3 = createSchedule(id3);

            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(successResult());
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(List.of(s1, s2, s3));

            service.checkAndExecuteSchedules();

            verify(triggerService, times(3)).executeTrigger(any(), any(), any(), any());
            // Workflow schedules: no recordScheduleExecution (advance is pre-execution)
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("One schedule fails → others still execute")
        void oneFailureDoesNotBlockOthers() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            ScheduledExecutionDto s1 = createSchedule(id1);
            ScheduledExecutionDto s2 = createSchedule(id2);

            // s1 → workflow not found (prepareScheduleExecution returns null)
            // s2 → happy path
            UUID wf1 = s1.getWorkflowId();
            UUID wf2 = UUID.randomUUID();
            s2.setWorkflowId(wf2);

            WorkflowEntity workflow2 = new WorkflowEntity();
            workflow2.setId(wf2);
            WorkflowRunEntity run2 = new WorkflowRunEntity();
            run2.setRunIdPublic("run-2");
            run2.setStatus(RunStatus.WAITING_TRIGGER);
            run2.setExecutionMode(ExecutionMode.AUTOMATIC);

            when(workflowRepository.findById(wf1)).thenReturn(Optional.empty());
            when(workflowRepository.findById(wf2)).thenReturn(Optional.of(workflow2));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wf2))
                    .thenReturn(Optional.of(run2));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(successResult());
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(List.of(s1, s2));

            service.checkAndExecuteSchedules();

            // PR1 (round-7): s1 SKIPPED (no workflow → no disable, will retry next tick),
            // s2 executed normally - confirms one bad schedule doesn't poison the loop.
            verify(triggerClient, never()).disableSchedule(any());
            verify(triggerService, times(1)).executeTrigger(any(), any(), any(), any());
            // Workflow schedules: no recordScheduleExecution (advance is pre-execution)
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("Exception in one schedule execution → others still execute")
        void exceptionInOneDoesNotBlockOthers() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            ScheduledExecutionDto s1 = createSchedule(id1);
            ScheduledExecutionDto s2 = createSchedule(id2);

            UUID wf2 = UUID.randomUUID();
            s2.setWorkflowId(wf2);

            WorkflowEntity workflow1 = createWorkflow(null);
            WorkflowEntity workflow2 = new WorkflowEntity();
            workflow2.setId(wf2);
            WorkflowRunEntity run1 = createRun(RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity run2 = new WorkflowRunEntity();
            run2.setRunIdPublic("run-2");
            run2.setStatus(RunStatus.WAITING_TRIGGER);
            run2.setExecutionMode(ExecutionMode.AUTOMATIC);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow1));
            when(workflowRepository.findById(wf2)).thenReturn(Optional.of(workflow2));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run1));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wf2))
                    .thenReturn(Optional.of(run2));

            // s1 throws, s2 succeeds
            when(triggerService.executeTrigger(eq(run1), any(), any(), any()))
                    .thenThrow(new RuntimeException("Simulated crash"));
            when(triggerService.executeTrigger(eq(run2), any(), any(), any()))
                    .thenReturn(successResult());

            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(List.of(s1, s2));

            service.checkAndExecuteSchedules();

            // Both were attempted - workflow schedules no longer call recordScheduleExecution
            // (advance is pre-execution via claimAndAdvanceDueSchedules)
            verify(triggerClient, never()).recordScheduleExecution(any());
            // s2 still executed even though s1 threw
            verify(triggerService).executeTrigger(eq(run2), any(), any(), any());
        }
    }

    // ==================== Restart Scenario Simulation ====================

    @Nested
    @DisplayName("Restart Scenario - overdue schedules on daemon start")
    class RestartScenarioTests {

        @Test
        @DisplayName("Overdue schedule after restart: should fire once and advance (not retry infinitely)")
        void overdueScheduleFiresOnceAfterRestart() {
            ScheduledExecutionDto schedule = createSchedule();
            // Simulate: nextExecutionAt was 2 hours ago
            schedule.setNextExecutionAt(Instant.now().minusSeconds(7200));
            schedule.setExecutionCount(10);

            stubHappyPath();
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(List.of(schedule));

            // Tick 1: should fire
            service.checkAndExecuteSchedules();
            // Workflow schedule: no recordScheduleExecution (advance is pre-execution)
            verify(triggerClient, never()).recordScheduleExecution(any());
            verify(triggerService, times(1)).executeTrigger(any(), any(), any(), any());

            // Tick 2: in a real scenario, trigger-service would have advanced nextExecutionAt
            // to the future, so findDueSchedules would return empty
            reset(triggerClient, triggerService);
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(Collections.emptyList());

            service.checkAndExecuteSchedules();

            // No more executions
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Multiple overdue schedules after restart: all executed (advance was pre-execution)")
        void multipleOverdueSchedulesAllExecute() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            ScheduledExecutionDto s1 = createSchedule(id1);
            s1.setNextExecutionAt(Instant.now().minusSeconds(3600));
            ScheduledExecutionDto s2 = createSchedule(id2);
            s2.setNextExecutionAt(Instant.now().minusSeconds(7200));

            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(successResult());
            when(triggerClient.claimAndAdvanceDueSchedules(any())).thenReturn(List.of(s1, s2));

            service.checkAndExecuteSchedules();

            // Both executed (advance already happened in claimAndAdvanceDueSchedules)
            verify(triggerClient, never()).recordScheduleExecution(any());
            verify(triggerService, times(2)).executeTrigger(any(), any(), any(), any());
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("findDueSchedules HTTP failure → daemon does not crash")
        void findDueSchedulesFailure() {
            when(triggerClient.claimAndAdvanceDueSchedules(any()))
                    .thenThrow(new RuntimeException("HTTP 503"));

            // Should not throw - the daemon must be resilient
            try {
                service.checkAndExecuteSchedules();
            } catch (RuntimeException e) {
                // If it does throw, the test will document that behavior
                // In current code, findDueSchedules catches internally and returns empty list
            }

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("executeSchedule called directly (not via daemon) → no guard (public method)")
        void directCallHasNoGuard() {
            ScheduledExecutionDto schedule = createSchedule();
            stubHappyPath();

            // Direct call (not through checkAndExecuteSchedules)
            service.executeSchedule(schedule);

            // Workflow schedule: no recordScheduleExecution (advance is pre-execution)
            verify(triggerClient, never()).recordScheduleExecution(any());
            verify(triggerService).executeTrigger(any(), any(), any(), any());
            // No guard active outside daemon
            assertThat(service.isScheduleExecuting(SCHEDULE_ID)).isFalse();
        }

        @Test
        @DisplayName("Payload should contain correct schedule metadata")
        void payloadContainsScheduleMetadata() {
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setExecutionCount(5);
            schedule.setCronExpression("0 */2 * * *");
            schedule.setTimezone("Europe/Paris");
            schedule.setNextExecutionAt(Instant.parse("2026-05-22T10:15:00Z"));

            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(any(), any(), any(), any()))
                    .thenReturn(successResult());

            service.executeSchedule(schedule);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(triggerService).executeTrigger(any(), any(), any(), captor.capture());

            Map<String, Object> payload = captor.getValue();
            assertThat(payload).containsEntry("executionCount", 6); // 5 + 1
            assertThat(payload).containsEntry("cron", "0 */2 * * *");
            assertThat(payload).containsEntry("timezone", "Europe/Paris");
            assertThat(payload).containsEntry("scheduleId", SCHEDULE_ID.toString());
            assertThat(payload).containsEntry("triggerId", TRIGGER_ID);
            assertThat(payload).containsEntry("nextExecution", "2026-05-22T10:15:00Z");
            assertThat(payload).containsKey("triggeredAt");
            // Workflow schedule: no recordScheduleExecution (advance is pre-execution)
            verify(triggerClient, never()).recordScheduleExecution(any());
        }

        @Test
        @DisplayName("Workflow schedule executes without calling recordScheduleExecution (advance is pre-execution)")
        void workflowScheduleExecutesWithoutRecordScheduleExecution() {
            ScheduledExecutionDto schedule = createSchedule();
            stubHappyPath();

            service.executeSchedule(schedule);

            verify(triggerService).executeTrigger(any(), any(), any(), any());
            // Workflow schedules: advance happened in claimAndAdvanceDueSchedules
            verify(triggerClient, never()).recordScheduleExecution(any());
        }
    }

    @Nested
    @DisplayName("Cron-storm jitter (regression guard for OOM 2026-05-06 12:22)")
    class CronStormJitterTests {

        @Test
        @DisplayName("Single schedule fires with delay=0 (no spread)")
        void singleScheduleFiresImmediately() {
            ScheduledExecutionDto s = scheduleDtoForWorkflow();
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(List.of(s));

            java.util.List<Long> recordedDelays = new java.util.ArrayList<>();
            service.setSpreadDispatcherForTesting((task, delayMs) -> {
                recordedDelays.add(delayMs);
                task.run();
            });

            service.checkAndExecuteSchedules();

            assertThat(recordedDelays).hasSize(1);
            assertThat(recordedDelays.get(0)).isZero();
        }

        @Test
        @DisplayName("N schedules dispatch with monotonically increasing delays spread across the window")
        void multipleSchedulesAreSpread() throws Exception {
            // Set the spread window via reflection (no @Value injection in unit tests).
            java.lang.reflect.Field f = ScheduleExecutorService.class.getDeclaredField("spreadMaxMs");
            f.setAccessible(true);
            f.setLong(service, 30_000L);

            int n = 10;
            java.util.List<ScheduledExecutionDto> schedules = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                ScheduledExecutionDto sd = scheduleDtoForWorkflow(UUID.randomUUID(), UUID.randomUUID());
                schedules.add(sd);
            }
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(schedules);

            java.util.List<Long> recordedDelays = new java.util.ArrayList<>();
            service.setSpreadDispatcherForTesting((task, delayMs) -> {
                recordedDelays.add(delayMs);
                // Don't actually execute - tests focus on the spread contract,
                // executeSchedule has its own coverage elsewhere.
            });

            service.checkAndExecuteSchedules();

            assertThat(recordedDelays).hasSize(n);
            // Delay 0 → ~3000ms with jitter.
            // Each delay = i * (30000/10) = i*3000 + jitter[0..500).
            for (int i = 0; i < n; i++) {
                long expectedFloor = (long) i * 3000L;
                long expectedCeil = expectedFloor + 500L;
                long actual = recordedDelays.get(i);
                assertThat(actual)
                    .as("Delay for schedule index %d", i)
                    .isBetween(expectedFloor, expectedCeil);
            }
            // Strict monotonic for index-pairs spaced more than the jitter window.
            assertThat(recordedDelays.get(0)).isLessThan(recordedDelays.get(1));
            assertThat(recordedDelays.get(n - 2)).isLessThan(recordedDelays.get(n - 1));
        }

        @Test
        @DisplayName("Each dispatched schedule actually invokes executeSchedule + cleans up the guard (e2e)")
        void dispatchedSchedulesActuallyExecute() throws Exception {
            // Audit follow-up F1: prior tests captured delays but discarded the
            // task. Ensure runScheduleSafely is actually called per dispatch and
            // that the executingScheduleIds guard is removed afterwards (so the
            // gauge returns to 0 on quiescence, the WorkflowScheduleStorm alert
            // doesn't latch indefinitely).
            java.lang.reflect.Field f = ScheduleExecutorService.class.getDeclaredField("spreadMaxMs");
            f.setAccessible(true);
            f.setLong(service, 0L); // no spread - pure synchronous in this test

            int n = 3;
            java.util.List<ScheduledExecutionDto> schedules = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                ScheduledExecutionDto sd = scheduleDtoForWorkflow(UUID.randomUUID(), UUID.randomUUID());
                schedules.add(sd);
            }
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(schedules);

            // Default test setUp dispatcher = synchronous → tasks run inline.
            // This exercises the full deferred-execution path.
            service.checkAndExecuteSchedules();

            // The guard map must be empty after all dispatched tasks complete:
            // the runScheduleSafely finally-block removes each id.
            java.lang.reflect.Field guardField = ScheduleExecutorService.class.getDeclaredField("executingScheduleIds");
            guardField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, Boolean> guard =
                (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) guardField.get(service);
            assertThat(guard).as("Guard must be empty after all schedules complete").isEmpty();
        }

        @Test
        @DisplayName("Heap pressure → tick skipped BEFORE claiming - claimAndAdvanceDueSchedules never called, schedules remain due")
        void heapPressureSkipsBeforeClaimSoSchedulesRemainDue() {
            // Regression guard: claimAndAdvanceDueSchedules atomically advances
            // nextExecutionAt in the trigger-service transaction. If we claimed
            // first and then skipped, advanced schedules would be lost until their
            // next cron occurrence. The heap check MUST run before the claim call.
            ScheduleExecutorService spy = spy(service);
            doReturn(true).when(spy).isHeapUnderPressure();

            spy.checkAndExecuteSchedules();

            // CRITICAL: claimAndAdvanceDueSchedules must NOT be called at all
            // when heap is under pressure - calling it would advance nextExecutionAt.
            verify(triggerClient, never()).claimAndAdvanceDueSchedules(any(Instant.class));
            verify(triggerClient, never()).findDueSchedules(any(Instant.class));
            verify(triggerClient, never()).recordScheduleExecution(any(UUID.class));
            assertThat(spy.getTicksSkippedUnderPressure())
                .as("One full tick was skipped")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("Heap healthy → tick proceeds normally, all schedules dispatch")
        void heapHealthyDispatchesNormally() {
            ScheduleExecutorService spy = spy(service);
            doReturn(false).when(spy).isHeapUnderPressure();

            ScheduledExecutionDto s = scheduleDtoForWorkflow();
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(List.of(s));

            java.util.List<Long> recordedDelays = new java.util.ArrayList<>();
            spy.setSpreadDispatcherForTesting((task, delayMs) -> recordedDelays.add(delayMs));

            spy.checkAndExecuteSchedules();

            assertThat(spy.getTicksSkippedUnderPressure()).isZero();
            assertThat(recordedDelays).as("Schedule must be dispatched normally when heap is OK").hasSize(1);
        }

        @Test
        @DisplayName("Dispatcher throws (e.g. RejectedExecutionException at shutdown) → executingScheduleIds is freed for retry")
        void dispatcherFailureFreesGuardForRetry() throws Exception {
            ScheduleExecutionDtoStubFactory factory = new ScheduleExecutionDtoStubFactory();
            ScheduledExecutionDto s = scheduleDtoForWorkflow();
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(List.of(s));

            // Dispatcher rejects (simulates spreadScheduler.shutdown() + late dispatch).
            service.setSpreadDispatcherForTesting((task, delayMs) -> {
                throw new java.util.concurrent.RejectedExecutionException("spread-scheduler shutdown");
            });

            service.checkAndExecuteSchedules();

            // The guard MUST be empty - without the try/catch+remove, the schedule
            // would be permanently in-flight in the local map and skipped at every
            // subsequent tick.
            java.lang.reflect.Field guardField = ScheduleExecutorService.class
                .getDeclaredField("executingScheduleIds");
            guardField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, Boolean> guard =
                (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) guardField.get(service);
            assertThat(guard).as("Guard must be cleared so the next tick can retry").isEmpty();
        }

        @Test
        @DisplayName("Dispatcher throws non-rejection RuntimeException → guard freed AND exception propagates (audit round-4 #6)")
        void dispatcherRuntimeExceptionFreesGuardAndPropagates() throws Exception {
            // Audit 2026-05-06 round 4 #6: round-2 narrowed `catch (RuntimeException)`
            // to `catch (RejectedExecutionException)` so genuine bugs (NPE,
            // ClassCastException) propagate instead of being silently logged.
            // BUT without the new fallback `catch (RuntimeException) { remove; throw; }`,
            // a propagating bug would leak `executingScheduleIds` - the schedule would
            // be locked out of every subsequent tick until JVM restart. This test
            // proves both invariants in one shot: the genuine exception escapes AND
            // the guard is cleared so the next tick can retry once the bug is fixed.
            ScheduledExecutionDto s = scheduleDtoForWorkflow();
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(List.of(s));

            // Simulate a genuine dispatch bug - anything other than RejectedExecutionException.
            NullPointerException dispatchBug = new NullPointerException("buggy dispatcher");
            service.setSpreadDispatcherForTesting((task, delayMs) -> { throw dispatchBug; });

            // The exception MUST propagate - operator visibility is the whole point of the
            // round-2 narrowing. Capture it via assertThatThrownBy.
            org.assertj.core.api.Assertions.assertThatThrownBy(service::checkAndExecuteSchedules)
                .as("Genuine dispatch bug must propagate, not be silently swallowed")
                .isSameAs(dispatchBug);

            // AND the guard must be cleared - otherwise the schedule stays in-flight forever.
            java.lang.reflect.Field guardField = ScheduleExecutorService.class
                .getDeclaredField("executingScheduleIds");
            guardField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, Boolean> guard =
                (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) guardField.get(service);
            assertThat(guard)
                .as("Guard MUST be freed so the next tick can re-pick this schedule once the bug is fixed")
                .isEmpty();
        }

        // Local factory stub kept here to avoid polluting the broader test surface.
        private static class ScheduleExecutionDtoStubFactory {}

        @Test
        @DisplayName("spreadMaxMs=0 honours opt-out - no jitter, all schedules dispatch with delay=0")
        void spreadMaxMsZeroDisablesAllSpread() throws Exception {
            java.lang.reflect.Field f = ScheduleExecutorService.class.getDeclaredField("spreadMaxMs");
            f.setAccessible(true);
            f.setLong(service, 0L);

            int n = 5;
            java.util.List<ScheduledExecutionDto> schedules = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                schedules.add(scheduleDtoForWorkflow(UUID.randomUUID(), UUID.randomUUID()));
            }
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(schedules);

            java.util.List<Long> recordedDelays = new java.util.ArrayList<>();
            service.setSpreadDispatcherForTesting((task, delayMs) -> recordedDelays.add(delayMs));

            service.checkAndExecuteSchedules();

            // Operator explicitly asked for no spread - no jitter should leak in.
            assertThat(recordedDelays).hasSize(n);
            assertThat(recordedDelays).allSatisfy(d -> assertThat(d).isZero());
        }

        @Test
        @DisplayName("Per-schedule executingScheduleIds guard is set BEFORE dispatch (not inside the deferred task)")
        void guardIsSetSynchronously() {
            ScheduledExecutionDto s = scheduleDtoForWorkflow();
            when(triggerClient.claimAndAdvanceDueSchedules(any(Instant.class))).thenReturn(List.of(s));

            java.util.List<Boolean> guardObservedAtDispatch = new java.util.ArrayList<>();
            // Capture the guard state at dispatch time (before the task runs)
            service.setSpreadDispatcherForTesting((task, delayMs) -> {
                // The guard must already be set when dispatch is called - that is
                // what protects the next minute-tick from re-firing in-flight ones.
                guardObservedAtDispatch.add(executingScheduleIdsContains(s.getId()));
                // Don't run the task - keep the guard set.
            });

            service.checkAndExecuteSchedules();

            assertThat(guardObservedAtDispatch).containsExactly(Boolean.TRUE);
        }

        private boolean executingScheduleIdsContains(UUID scheduleId) {
            try {
                java.lang.reflect.Field f = ScheduleExecutorService.class.getDeclaredField("executingScheduleIds");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.concurrent.ConcurrentHashMap<UUID, Boolean> map =
                    (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) f.get(service);
                return map.containsKey(scheduleId);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private ScheduledExecutionDto scheduleDtoForWorkflow() {
            return scheduleDtoForWorkflow(SCHEDULE_ID, WORKFLOW_ID);
        }

        private ScheduledExecutionDto scheduleDtoForWorkflow(UUID scheduleId, UUID workflowId) {
            ScheduledExecutionDto sd = new ScheduledExecutionDto();
            sd.setId(scheduleId);
            sd.setWorkflowId(workflowId);
            sd.setTriggerId(TRIGGER_ID);
            sd.setEnabled(true);
            sd.setCronExpression("0 * * * *");
            return sd;
        }
    }

    @Nested
    @DisplayName("Concurrency observability gauge")
    class ConcurrencyGaugeTests {

        @Test
        @DisplayName("workflow_trigger_fire_concurrency gauge reflects executingScheduleIds size in real time")
        void gaugeTracksLiveConcurrency() throws Exception {
            // Regression guard for OOM 2026-05-06 12:22 - alert WorkflowScheduleStorm
            // depends on this gauge to detect cron storm at HH:00. The gauge must
            // reflect ConcurrentHashMap.size() live (no caching, no stale value).
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            ScheduleExecutorService svc = new ScheduleExecutorService(
                triggerClient, workflowRepository, runRepository, triggerService,
                productionRunResolver, agentClient, conversationServiceClient, registry);

            // Locate the gauge by name + tag, fail fast if it isn't registered.
            io.micrometer.core.instrument.Gauge gauge = registry
                .find("workflow_trigger_fire_concurrency")
                .tag("type", "SCHEDULE")
                .gauge();
            assertThat(gauge).as("gauge must be registered with type=SCHEDULE tag").isNotNull();

            // Initially empty.
            assertThat(gauge.value()).isEqualTo(0.0);

            // Reach into the in-memory guard via reflection (it's @private, no setter):
            // the guard IS the source of truth that the gauge mirrors, so we exercise
            // the contract end-to-end rather than mocking the gauge supplier.
            java.lang.reflect.Field field = ScheduleExecutorService.class
                .getDeclaredField("executingScheduleIds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, Boolean> guard =
                (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) field.get(svc);

            guard.put(UUID.randomUUID(), Boolean.TRUE);
            guard.put(UUID.randomUUID(), Boolean.TRUE);
            assertThat(gauge.value()).isEqualTo(2.0);

            guard.clear();
            assertThat(gauge.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("PR22c R3 - workspace-scope guard regression (R2 convergent must-fix A+B+C)")
    class WorkspaceScopeGuardTests {

        private static final String ORG_ID = "org-acme";
        private static final String OTHER_ORG_ID = "org-other";

        @Test
        @DisplayName("Workflow schedule: refuses fire when schedule org != run org - PR22 R2.4 guard")
        void workflowScheduleRefusesOnMismatch() {
            // Convergent R2 must-fix #2 (test coverage for fire-path guards). Without this
            // regression test the load-bearing guard at ScheduleExecutorService:445 has no
            // enforcement and a future refactor could silently strip it.
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setOrganizationId(ORG_ID);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(OTHER_ORG_ID);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflow(null)));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            service.executeSchedule(schedule);

            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Workflow schedule: fires when schedule org == run org")
        void workflowScheduleFiresOnMatch() {
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setOrganizationId(ORG_ID);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(ORG_ID);

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflow(null)));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE, Set.of(), 1));

            service.executeSchedule(schedule);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any());
        }

        @Test
        @DisplayName("Agent schedule: always routes through findOrCreateAgentConversation regardless of withMemory (regression: with_memory=false used to spawn an orphan conversation per fire)")
        void agentScheduleAlwaysReusesPrimaryConversation() {
            // Pre-fix: with_memory=false → conversationServiceClient.createConversation(...)
            // → new conv per cron tick, polluting the sidebar and breaking the agent's
            // single-home assumption. Post-fix: both withMemory branches collapse onto
            // findOrCreateAgentConversation; createConversation must NEVER be called from
            // the agent-schedule path. The withMemory flag stays on the schedule row but
            // only affects history loading at chat-time (conversation-service decision).
            UUID agentId = UUID.randomUUID();
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setAgentEntityId(agentId);
            schedule.setOrganizationId(ORG_ID);
            schedule.setSchedulePrompt("brand audit");
            schedule.setWithMemory(false); // the failing-prod scenario

            com.apimarketplace.agent.client.dto.AgentDto agent = new com.apimarketplace.agent.client.dto.AgentDto();
            agent.setId(agentId);
            agent.setName("Brand Distinctiveness Fixer");
            agent.setIsActive(true);
            agent.setOrganizationId(ORG_ID);
            agent.setModelProvider("deepseek");
            agent.setModelName("deepseek-chat");

            // 4-arg buildScheduledPrompt (with organizationId) is the prod schedule path.
            when(agentClient.buildScheduledPrompt(eq(agentId), any(), any(), any())).thenReturn("brand audit");
            // 3-arg getAgent (with organizationId) is what the daemon thread calls.
            when(agentClient.getAgent(eq(agentId), any(), any())).thenReturn(agent);
            when(conversationServiceClient.findOrCreateAgentConversation(
                    eq(agentId.toString()), any(), any(), any())).thenReturn("primary-conv-id");
            when(conversationServiceClient.sendChatSync(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", true, "content", "ok"));

            service.executeSchedule(schedule);

            verify(conversationServiceClient).findOrCreateAgentConversation(
                    eq(agentId.toString()), any(), eq("Brand Distinctiveness Fixer"), eq(ORG_ID));
            verify(conversationServiceClient).sendChatSync(
                    eq("tenant-1"), eq("primary-conv-id"), eq("brand audit"),
                    eq(agentId.toString()), eq("deepseek-chat"), eq("deepseek"),
                    eq("SCHEDULE"), isNull(), eq(ORG_ID));
            verify(conversationServiceClient, never()).createConversation(
                    any(), any(), any(), any(), any(), anyBoolean(), any());
            verify(conversationServiceClient, never()).createConversation(
                    any(), any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Agent schedule: refuses fire when schedule org != agent org - new PR22c R3 guard at executeAgentSchedule")
        void agentScheduleRefusesOnMismatch() {
            // Convergent R2 must-fix #4 (A+C). PR22b only added the guard on
            // executeWorkflowSchedule; the agent-schedule branch was silently
            // bypassed. This test pins the new PR22c R3 guard at
            // ScheduleExecutorService.executeAgentSchedule before advanceSchedule().
            UUID agentId = UUID.randomUUID();
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setAgentEntityId(agentId);
            schedule.setOrganizationId(ORG_ID);
            schedule.setSchedulePrompt("daily report");

            com.apimarketplace.agent.client.dto.AgentDto agent = new com.apimarketplace.agent.client.dto.AgentDto();
            agent.setId(agentId);
            agent.setName("Test Agent");
            agent.setIsActive(true);
            agent.setOrganizationId(OTHER_ORG_ID);

            when(agentClient.buildScheduledPrompt(eq(agentId), any(), any(), any())).thenReturn("daily report");
            when(agentClient.getAgent(eq(agentId), any(), any())).thenReturn(agent);

            service.executeSchedule(schedule);

            // Guard short-circuits before conversation creation.
            verify(conversationServiceClient, never()).createConversation(any(), any(), any(), any(), any(), anyBoolean());
            verify(conversationServiceClient, never()).findOrCreateAgentConversation(any(), any(), any());
        }

        @Test
        @DisplayName("Agent schedule: skips fire when optimistic advance archived the row")
        void agentScheduleSkipsWhenAdvanceArchivesSchedule() {
            UUID agentId = UUID.randomUUID();
            ScheduledExecutionDto schedule = createSchedule();
            schedule.setAgentEntityId(agentId);
            schedule.setOrganizationId(ORG_ID);
            schedule.setSchedulePrompt("daily report");

            com.apimarketplace.agent.client.dto.AgentDto agent = new com.apimarketplace.agent.client.dto.AgentDto();
            agent.setId(agentId);
            agent.setName("Test Agent");
            agent.setIsActive(true);
            agent.setOrganizationId(ORG_ID);

            ScheduledExecutionDto archived = createSchedule();
            archived.setEnabled(false);
            archived.setIsActive(false);

            when(agentClient.buildScheduledPrompt(eq(agentId), any(), any(), any())).thenReturn("daily report");
            when(agentClient.getAgent(eq(agentId), any(), any())).thenReturn(agent);
            when(triggerClient.recordScheduleExecution(SCHEDULE_ID)).thenReturn(archived);

            service.executeSchedule(schedule);

            verify(triggerClient).recordScheduleExecution(SCHEDULE_ID);
            verify(conversationServiceClient, never()).findOrCreateAgentConversation(any(), any(), any(), any());
            verify(conversationServiceClient, never()).sendChatSync(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
