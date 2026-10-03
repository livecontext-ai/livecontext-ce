package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPinService")
class WorkflowPinServiceTest {

    @Mock WorkflowRepository workflowRepository;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock WorkflowPlanVersionService versionService;
    @Mock PinAwareTriggerSyncService triggerSyncService;
    @Mock EntityManager entityManager;
    @Mock Query advisoryLockQuery;
    @Mock WorkflowExecutionService executionService;
    @Mock com.apimarketplace.orchestrator.services.persistence.ScheduleSyncService scheduleSyncService;

    /** Real detector, not a mock: the trigger predicate is the gate under test here. */
    private final TriggerTypeDetector triggerTypeDetector = new TriggerTypeDetector();

    private WorkflowPinService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-x";
    private static final String ORG_ID = "org-x";

    @BeforeEach
    void setUp() {
        // PR3: WorkflowPinService now acquires a Postgres advisory lock via EntityManager.
        // Stub the native query chain so unit tests don't need a real DB.
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(advisoryLockQuery);
        lenient().when(advisoryLockQuery.setParameter(anyString(), any())).thenReturn(advisoryLockQuery);
        lenient().when(advisoryLockQuery.getSingleResult()).thenReturn(0);

        service = new WorkflowPinService(workflowRepository, workflowRunRepository,
                versionService, entityManager, executionService, triggerTypeDetector,
                triggerSyncService, scheduleSyncService);
    }

    private WorkflowEntity workflow(String tenant, Integer pinned) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(WORKFLOW_ID);
        w.setTenantId(tenant);
        w.setPinnedVersion(pinned);
        return w;
    }

    /** WorkflowRunEntity has no public setId - use reflection in tests. */
    private static void setRunId(WorkflowRunEntity run, UUID id) {
        try {
            java.lang.reflect.Field f = WorkflowRunEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(run, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static WorkflowPlanVersionEntity version(Map<String, Object> plan) {
        WorkflowPlanVersionEntity v = new WorkflowPlanVersionEntity();
        v.setPlan(plan);
        return v;
    }

    /** A stored version whose plan carries a trigger production could fire. */
    private static WorkflowPlanVersionEntity versionWithWebhookTrigger() {
        return version(Map.of("triggers", List.of(Map.of("type", "webhook", "label", "hook"))));
    }

    /** A schedule-bearing version - the only shape whose refusal owes a schedule repair. */
    private static WorkflowPlanVersionEntity versionWithScheduleTrigger() {
        return version(Map.of("triggers",
                List.of(Map.of("type", "schedule", "label", "nightly"))));
    }

    private static WorkflowRunEntity provisionedRun(String runIdPublic, UUID id) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setRunIdPublic(runIdPublic);
        setRunId(run, id);
        return run;
    }

    /** WorkflowExecution needs a real plan - its constructor walks the execution graph. */
    private static WorkflowExecution execution(String runIdPublic) {
        Map<String, Object> planMap = new java.util.HashMap<>(
                Map.of("triggers", List.of(Map.of("type", "webhook", "label", "hook"))));
        return new WorkflowExecution(runIdPublic,
                WorkflowPlan.fromMap(planMap, WORKFLOW_ID.toString(), TENANT_ID),
                Map.of());
    }

    /**
     * Stub the provisioning path: createExecution mints {@code runIdPublic}, and the
     * repository resolves it to {@code persisted} ({@code null} = the row was never written).
     */
    private void stubProvisioning(String runIdPublic, WorkflowRunEntity persisted) {
        when(executionService.createExecution(any(WorkflowPlan.class), any(), any()))
                .thenReturn(execution(runIdPublic));
        when(workflowRunRepository.findByRunIdPublic(runIdPublic))
                .thenReturn(Optional.ofNullable(persisted));
    }

    @Nested
    @DisplayName("pin(version)")
    class PinTests {

        @Test
        @DisplayName("pins, sets production_run_id, and re-syncs triggers when version has a usable run")
        void pinsAndSyncs() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity run = new WorkflowRunEntity();
            setRunId(run, runId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(3), anyList()))
                    .thenReturn(Optional.of(run));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 3);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(((WorkflowPinService.PinResult.Success) result).pinnedVersion()).isEqualTo(3);
            assertThat(wf.getPinnedVersion()).isEqualTo(3);
            // PR3: production_run_id MUST be set in the same transaction as pinnedVersion.
            assertThat(wf.getProductionRunId()).isEqualTo(runId);
            verify(workflowRepository).save(wf);
            verify(triggerSyncService).syncAllTriggersFromPinnedVersion(wf);
            // PR3: pin acquires the per-workflow advisory lock.
            verify(entityManager).createNativeQuery(anyString());
            verify(advisoryLockQuery).setParameter(eq("key"), eq("trigger:pin:" + WORKFLOW_ID));
        }

        @Test
        @DisplayName("backfills null workflow organization from selected production run before trigger sync")
        void pinBackfillsOrganizationFromProductionRun() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity run = new WorkflowRunEntity();
            setRunId(run, runId);
            run.setOrganizationId(ORG_ID);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(3), anyList()))
                    .thenReturn(Optional.of(run));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, null, 3);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(wf.getOrganizationId()).isEqualTo(ORG_ID);
            verify(workflowRepository).save(wf);
            verify(triggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        }

        @Test
        @DisplayName("returns NotFound when workflow does not exist")
        void notFound() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());
            var result = service.pin(WORKFLOW_ID, TENANT_ID, 1);
            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.NotFound.class);
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("returns Forbidden when tenant mismatches")
        void forbidden() {
            WorkflowEntity wf = workflow("other-tenant", null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            var result = service.pin(WORKFLOW_ID, TENANT_ID, 1);
            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Forbidden.class);
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("org-tagged workflow is Forbidden when active org is dropped (the pin bug) but Succeeds when threaded")
        void orgTaggedWorkflowRequiresActiveOrgContext() {
            // Reproduces the WorkflowCrudModule pin bug: an org-tagged workflow
            // pinned with orgId=null (the old 3-arg path) fails strict scope and
            // is masked as "Workflow not found"; threading the caller's active
            // org makes the same pin succeed.
            WorkflowEntity wf = workflow(TENANT_ID, null);
            wf.setOrganizationId(ORG_ID);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            // Bug path - no active org passed → personal branch rejects the
            // org-tagged row → Forbidden.
            var dropped = service.pin(WORKFLOW_ID, TENANT_ID, null, 3);
            assertThat(dropped).isInstanceOf(WorkflowPinService.PinResult.Forbidden.class);
            verify(workflowRepository, never()).save(any());

            // Fix path - active org threaded → org branch matches → pin proceeds.
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(3), anyList()))
                    .thenReturn(Optional.of(new WorkflowRunEntity()));

            var threaded = service.pin(WORKFLOW_ID, TENANT_ID, ORG_ID, 3);
            assertThat(threaded).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(wf.getPinnedVersion()).isEqualTo(3);
            verify(workflowRepository).save(wf);
        }

        @Test
        @DisplayName("returns VersionNotFound when target version does not exist")
        void versionNotFound() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 9)).thenReturn(Optional.empty());

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 9);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.VersionNotFound.class);
            assertThat(((WorkflowPinService.PinResult.VersionNotFound) result).version()).isEqualTo(9);
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("provisions the production run when a triggerable version has none")
        void provisionsProductionRunWhenVersionHasNone() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            WorkflowRunEntity minted = provisionedRun("run_minted", UUID.randomUUID());
            stubProvisioning("run_minted", minted);

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            var success = (WorkflowPinService.PinResult.Success) result;
            assertThat(success.pinnedVersion()).isEqualTo(2);
            assertThat(success.productionRunIdPublic()).isEqualTo("run_minted");
            assertThat(wf.getPinnedVersion()).isEqualTo(2);
            assertThat(wf.getProductionRunId()).isEqualTo(minted.getId());
            // The version's frozen plan is what production runs, at that version number.
            verify(executionService).createExecution(any(WorkflowPlan.class), any(), eq(2));
            verify(workflowRepository).save(wf);
        }

        @Test
        @DisplayName("does not provision when a trusted run already exists at the version")
        void doesNotProvisionWhenTrustedRunExists() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            UUID runId = UUID.randomUUID();
            WorkflowRunEntity existing = new WorkflowRunEntity();
            existing.setStatus(RunStatus.WAITING_TRIGGER);
            existing.setRunIdPublic("run_existing");
            setRunId(existing, runId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.of(existing));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(wf.getProductionRunId()).isEqualTo(runId);
            verify(executionService, never()).createExecution(any(), any(), any());
        }

        @Test
        @DisplayName("pins with no production run when the version plan has no trigger to fire")
        void pinsWithoutRunWhenVersionHasNoTrigger() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(version(Map.of("triggers", List.of()))));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            // Nothing can fire it, so there is nothing to point at - but the pin still
            // selects the version for core:sub_workflow and execute(version='pinned').
            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(wf.getPinnedVersion()).isEqualTo(2);
            assertThat(wf.getProductionRunId()).isNull();
            // Provisioning a triggerless plan would leave a run stuck RUNNING forever.
            verify(executionService, never()).createExecution(any(), any(), any());
        }

        @Test
        @DisplayName("refuses the pin when provisioning throws, leaving the workflow untouched")
        void refusesPinWhenProvisioningThrows() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            when(executionService.createExecution(any(WorkflowPlan.class), any(), eq(2)))
                    .thenThrow(new IllegalStateException("plan rejected by the markup validator"));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
            var refused = (WorkflowPinService.PinResult.ProductionRunUnavailable) result;
            assertThat(refused.version()).isEqualTo(2);
            // A refusal must NEVER route through syncAllTriggersFromPinnedVersion: on the
            // restored NULL pin that lands in disableAllTriggers, whose orphan-token cleanup
            // passes an empty keep-list and hard-DELETES every webhook token of the workflow.
            // A refused pin changed nothing; killing live webhook URLs is not a repair.
            verify(triggerSyncService, never()).syncAllTriggersFromPinnedVersion(any());
            // No repair either. Note WHY this one holds: hasScheduleTrigger is unstubbed here,
            // so the mock answers false and the GATE stops it - this assertion does not prove
            // the ordering claim above. refusedPinWithoutScheduleRepairsNothing pins the gate
            // deliberately; refusedFirstPinDisablesSchedules pins the armed case.
            verify(scheduleSyncService, never()).syncFromPinnedVersion(any());
            // The exception message is LOGGED, never surfaced: this string reaches an MCP
            // agent and a user toast, so it must not carry a stacktrace or a classname.
            assertThat(refused.reason()).isEqualTo(WorkflowPinService.PROVISIONING_FAILED_REASON);
            assertThat(refused.reason()).doesNotContain("markup validator");
            // Half-pinning would arm the triggers against nothing.
            assertThat(wf.getPinnedVersion()).isNull();
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("a refused pin re-syncs schedules from the version production was already on")
        void refusedPinResyncsSchedulesFromThePreviousPin() {
            // Already pinned to v1; the pin to v2 fails AFTER the inner schedule sync could
            // have armed v2's cron, which is the only shape that owes a repair.
            WorkflowEntity wf = workflow(TENANT_ID, 1);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithScheduleTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            when(scheduleSyncService.hasScheduleTrigger(any(WorkflowPlan.class))).thenReturn(true);
            when(executionService.createExecution(any(WorkflowPlan.class), any(), eq(2)))
                    .thenThrow(new IllegalStateException("boom"));
            // Capture the pin AT CALL TIME. Mockito holds the argument by reference and
            // verifies after the fact, so asserting on the entity afterwards cannot tell a
            // correct restore-then-sync from a sync-then-restore.
            final Integer[] pinSeenBySync = new Integer[1];
            doAnswer(inv -> {
                pinSeenBySync[0] = ((WorkflowEntity) inv.getArgument(0)).getPinnedVersion();
                return null;
            }).when(scheduleSyncService).syncFromPinnedVersion(any());

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
            assertThat(pinSeenBySync[0])
                    .as("the repair must run AFTER the pin is restored, or it re-arms the refused version")
                    .isEqualTo(1);
            // And still never the full teardown, which would delete the webhook tokens.
            verify(triggerSyncService, never()).syncAllTriggersFromPinnedVersion(any());

            // The gate must be the SAME predicate recordWorkflowStart uses, asked about the
            // SAME object - otherwise it is two predicates over two representations, which
            // is the divergence the sibling hasReusableTrigger gate carries a warning about.
            // Asserting only "we honoured whatever the stub answered" would stay green if
            // the production gate were swapped to TriggerTypeDetector.hasScheduleTrigger,
            // which is a real same-named method on the other collaborator this service holds.
            ArgumentCaptor<WorkflowPlan> planHandedToCreate = ArgumentCaptor.forClass(WorkflowPlan.class);
            verify(executionService).createExecution(planHandedToCreate.capture(), any(), eq(2));
            verify(scheduleSyncService).hasScheduleTrigger(same(planHandedToCreate.getValue()));
        }

        @Test
        @DisplayName("a refused FIRST pin on a schedule workflow leaves the schedules disabled")
        void refusedFirstPinDisablesSchedules() {
            // The shape that carried the destructive round-4 defect: never pinned before, so
            // the restored pin is NULL and the repair takes the disableAllSchedules branch.
            // Correct in production, but nothing guarded it - a regression that skipped the
            // repair when there was no previous pin would have shipped green.
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithScheduleTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            when(scheduleSyncService.hasScheduleTrigger(any(WorkflowPlan.class))).thenReturn(true);
            when(executionService.createExecution(any(WorkflowPlan.class), any(), eq(2)))
                    .thenThrow(new IllegalStateException("boom"));
            final Integer[] pinSeenBySync = new Integer[1];
            final boolean[] syncRan = new boolean[1];
            doAnswer(inv -> {
                syncRan[0] = true;
                pinSeenBySync[0] = ((WorkflowEntity) inv.getArgument(0)).getPinnedVersion();
                return null;
            }).when(scheduleSyncService).syncFromPinnedVersion(any());

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
            assertThat(syncRan[0]).as("the repair must still run when there was no previous pin").isTrue();
            // A NULL pin is what routes ScheduleSyncService into disableAllSchedules, which
            // SUSPENDS the rows (reversible by the next pin) rather than deleting them.
            assertThat(pinSeenBySync[0])
                    .as("with no previous pin the repair must sync from NULL, so the schedules go back to suspended")
                    .isNull();
            verify(triggerSyncService, never()).syncAllTriggersFromPinnedVersion(any());
        }

        @Test
        @DisplayName("a refused pin on a version with no schedule repairs nothing")
        void refusedPinWithoutScheduleRepairsNothing() {
            WorkflowEntity wf = workflow(TENANT_ID, 1);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            when(scheduleSyncService.hasScheduleTrigger(any(WorkflowPlan.class))).thenReturn(false);
            when(executionService.createExecution(any(WorkflowPlan.class), any(), eq(2)))
                    .thenThrow(new IllegalStateException("boom"));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
            // recordWorkflowStart only syncs schedules for a plan that HAS one, so without a
            // schedule nothing was armed. Repairing what was never touched is how a fix
            // becomes a defect.
            verify(scheduleSyncService, never()).syncFromPinnedVersion(any());
            verify(triggerSyncService, never()).syncAllTriggersFromPinnedVersion(any());
        }

        @Test
        @DisplayName("refuses the pin when the provisioned run was never persisted")
        void refusesPinWhenProvisionedRunIsMissing() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            // recordWorkflowStart logs and swallows its own failures, so the execution can
            // come back with a runId that has no row behind it.
            stubProvisioning("run_ghost", null);

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.ProductionRunUnavailable.class);
            assertThat(wf.getPinnedVersion()).isNull();
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("keeps lastExecutedAt untouched - provisioning executes nothing")
        void provisioningDoesNotClaimAnExecution() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            Instant lastReal = Instant.parse("2026-01-02T03:04:05Z");
            wf.setLastExecutedAt(lastReal);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionWithWebhookTrigger()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());
            WorkflowRunEntity minted = provisionedRun("run_minted", UUID.randomUUID());
            // Mimic recordWorkflowStart's side effect on the shared managed entity.
            when(executionService.createExecution(any(WorkflowPlan.class), any(), eq(2)))
                    .thenAnswer(inv -> {
                        wf.setLastExecutedAt(Instant.parse("2026-08-25T10:00:00Z"));
                        return execution("run_minted");
                    });
            when(workflowRunRepository.findByRunIdPublic("run_minted"))
                    .thenReturn(Optional.of(minted));

            service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(wf.getLastExecutedAt()).isEqualTo(lastReal);
        }

        @Test
        @DisplayName("WAITING_TRIGGER counts as a usable run")
        void waitingTriggerIsUsable() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setStatus(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(1),
                            eq(List.of(RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER,
                                    RunStatus.RUNNING, RunStatus.PAUSED))))
                    .thenReturn(Optional.of(run));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 1);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
        }

        @Test
        @DisplayName("sync failure does not block pin success")
        void syncFailureDoesNotBlockSuccess() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(3), anyList()))
                    .thenReturn(Optional.of(new WorkflowRunEntity()));
            org.mockito.Mockito.doThrow(new RuntimeException("sync boom"))
                    .when(triggerSyncService).syncAllTriggersFromPinnedVersion(wf);

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 3);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(wf.getPinnedVersion()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("pin(null) - unpin")
    class UnpinTests {

        @Test
        @DisplayName("clears pinnedVersion + production_run_id and re-syncs triggers")
        void unpins() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID()); // simulate pre-existing pin
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, null);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
            assertThat(((WorkflowPinService.PinResult.Success) result).pinnedVersion()).isNull();
            assertThat(wf.getPinnedVersion()).isNull();
            // PR3: unpin clears production_run_id atomically with pinned_version.
            assertThat(wf.getProductionRunId()).isNull();
            verify(workflowRepository).save(wf);
            verify(triggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        }

        private com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService closesIncidents() {
            var delivery = org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService.class);
            service.setNotificationDelivery(delivery);
            return delivery;
        }

        @Test
        @DisplayName("Regression (reminder after a stop): unpin closes the workflow's failure incident")
        void unpinClosesTheFailureIncident() {
            var delivery = closesIncidents();
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow(TENANT_ID, 5)));

            service.pin(WORKFLOW_ID, TENANT_ID, null);

            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("A refused unpin closes nothing")
        void refusedUnpinClosesNothing() {
            var delivery = closesIncidents();
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow("other-tenant", 5)));

            assertThat(service.pin(WORKFLOW_ID, TENANT_ID, null))
                    .isInstanceOf(WorkflowPinService.PinResult.Forbidden.class);

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("A pin (the workflow keeps running) closes nothing")
        void pinClosesNothing() {
            var delivery = closesIncidents();
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow(TENANT_ID, 5)));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(3), anyList()))
                    .thenReturn(Optional.of(new WorkflowRunEntity()));

            assertThat(service.pin(WORKFLOW_ID, TENANT_ID, 3))
                    .isInstanceOf(WorkflowPinService.PinResult.Success.class);

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("skips version lookups when unpinning")
        void unpinSkipsVersionLookup() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            service.pin(WORKFLOW_ID, TENANT_ID, null);

            verify(versionService, never()).getVersion(any(), any(Integer.class));
            verify(workflowRunRepository, never())
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("unpin on foreign tenant returns Forbidden")
        void unpinForbidden() {
            WorkflowEntity wf = workflow("other-tenant", 5);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            var result = service.pin(WORKFLOW_ID, TENANT_ID, null);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Forbidden.class);
            assertThat(wf.getPinnedVersion()).isEqualTo(5);
            verify(workflowRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("service works without PinAwareTriggerSyncService (optional bean)")
    void worksWithoutSyncService() {
        var noSyncService = new WorkflowPinService(
                workflowRepository, workflowRunRepository, versionService, entityManager,
                executionService, triggerTypeDetector, null, null);
        WorkflowEntity wf = workflow(TENANT_ID, null);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

        var result = noSyncService.pin(WORKFLOW_ID, TENANT_ID, null);

        assertThat(result).isInstanceOf(WorkflowPinService.PinResult.Success.class);
    }

    @Nested
    @DisplayName("rearm() - PR3 RunTerminationListener entry point")
    class RearmTests {

        @Test
        @DisplayName("rearm with TRUSTED run available → updates production_run_id, returns true")
        void rearmFindsTrustedRun() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID()); // stale (just-terminated) run
            UUID newRunId = UUID.randomUUID();
            WorkflowRunEntity newRun = new WorkflowRunEntity();
            setRunId(newRun, newRunId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5), anyList()))
                    .thenReturn(Optional.of(newRun));

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isTrue();
            assertThat(wf.getProductionRunId()).isEqualTo(newRunId);
            verify(workflowRepository).save(wf);
            verify(triggerSyncService).syncAllTriggersFromPinnedVersion(wf);
            // PR3: rearm acquires the same advisory lock as pin.
            verify(advisoryLockQuery).setParameter(eq("key"), eq("trigger:pin:" + WORKFLOW_ID));
        }

        @Test
        @DisplayName("rearm with NO trusted run → clears production_run_id, returns false")
        void rearmClearsWhenNoTrustedRun() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5), anyList()))
                    .thenReturn(Optional.empty());

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isFalse();
            assertThat(wf.getProductionRunId()).isNull();
            verify(workflowRepository).save(wf);
            // No sync when production_run_id was cleared - caller must suspend the trigger.
            verify(triggerSyncService, never()).syncAllTriggersFromPinnedVersion(any());
        }

        /**
         * Regression 2026-07-21 (round-4 audit): the plain newest-TRUSTED election
         * could pick a newer COMPLETED run over a live WAITING_TRIGGER one, turning a
         * FAILED/CANCELLED termination into a permanent deliberate-stop stall (a
         * COMPLETED production FK resolves EMPTY on the schedule lane forever).
         */
        @Test
        @DisplayName("rearm prefers a LIVE run over a newer COMPLETED one (no deliberate-stop conversion)")
        void rearmPrefersLiveRunOverNewerCompleted() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID());
            UUID liveRunId = UUID.randomUUID();
            WorkflowRunEntity liveRun = new WorkflowRunEntity();
            setRunId(liveRun, liveRunId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED))))
                    .thenReturn(Optional.of(liveRun));

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isTrue();
            assertThat(wf.getProductionRunId()).isEqualTo(liveRunId);
            // The full-TRUSTED (COMPLETED-including) election is never consulted when a live run exists.
            verify(workflowRunRepository, never())
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER,
                                    RunStatus.RUNNING, RunStatus.PAUSED)));
        }

        /**
         * Regression 2026-07-21 (round-5 audit, HIGH): with the only live run parked
         * AWAITING_SIGNAL (routine for approval workflows), the COMPLETED fallback
         * froze the FK on a deliberate-stop identity nothing ever heals (COMPLETED is
         * exempt from the resolver heal; the listener never fires for it again).
         * Rearm must clear the FK instead: the FK-null bootstrap scan then SERVES the
         * signal run once its approval resolves and it parks WAITING_TRIGGER (the FK
         * itself stays NULL until a later pin/rearm/termination re-points it).
         */
        @Test
        @DisplayName("rearm clears the FK (never elects COMPLETED) when the only live run is AWAITING_SIGNAL")
        void rearmClearsFkWhenOnlyAwaitingSignalRunSurvives() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID());
            WorkflowRunEntity awaitingRun = new WorkflowRunEntity();
            setRunId(awaitingRun, UUID.randomUUID());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED))))
                    .thenReturn(Optional.empty());
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5), eq(List.of(RunStatus.AWAITING_SIGNAL))))
                    .thenReturn(Optional.of(awaitingRun));

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isFalse();
            assertThat(wf.getProductionRunId()).isNull();
            verify(workflowRepository).save(wf);
            // The COMPLETED-including election is never consulted - it is exactly
            // what must not win here.
            verify(workflowRunRepository, never())
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER,
                                    RunStatus.RUNNING, RunStatus.PAUSED)));
        }

        @Test
        @DisplayName("rearm falls back to a COMPLETED survivor when no live run exists (pre-existing contract kept)")
        void rearmFallsBackToCompletedWhenNoLiveRun() {
            WorkflowEntity wf = workflow(TENANT_ID, 5);
            wf.setProductionRunId(UUID.randomUUID());
            UUID completedId = UUID.randomUUID();
            WorkflowRunEntity completedRun = new WorkflowRunEntity();
            setRunId(completedRun, completedId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED))))
                    .thenReturn(Optional.empty());
            // No run parked on a blocking signal either - the COMPLETED fallback applies.
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5), eq(List.of(RunStatus.AWAITING_SIGNAL))))
                    .thenReturn(Optional.empty());
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(5),
                            eq(List.of(RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER,
                                    RunStatus.RUNNING, RunStatus.PAUSED))))
                    .thenReturn(Optional.of(completedRun));

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isTrue();
            assertThat(wf.getProductionRunId()).isEqualTo(completedId);
        }

        @Test
        @DisplayName("rearm on unpinned workflow → no-op, returns false")
        void rearmNoOpOnUnpinned() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isFalse();
            verify(workflowRepository, never()).save(any());
            verify(workflowRunRepository, never())
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("rearm on missing workflow → no-op, returns false")
        void rearmNoOpOnMissingWorkflow() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            boolean result = service.rearm(WORKFLOW_ID);

            assertThat(result).isFalse();
            verify(workflowRepository, never()).save(any());
        }
    }
}
