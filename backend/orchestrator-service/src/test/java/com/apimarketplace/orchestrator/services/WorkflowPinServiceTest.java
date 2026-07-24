package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                versionService, entityManager, triggerSyncService);
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
        @DisplayName("returns NoSuccessfulRun when version exists but has no usable run")
        void noSuccessfulRun() {
            WorkflowEntity wf = workflow(TENANT_ID, null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(new WorkflowPlanVersionEntity()));
            when(workflowRunRepository
                    .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                            eq(WORKFLOW_ID), eq(2), anyList()))
                    .thenReturn(Optional.empty());

            var result = service.pin(WORKFLOW_ID, TENANT_ID, 2);

            assertThat(result).isInstanceOf(WorkflowPinService.PinResult.NoSuccessfulRun.class);
            assertThat(((WorkflowPinService.PinResult.NoSuccessfulRun) result).version()).isEqualTo(2);
            verify(workflowRepository, never()).save(any());
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
                workflowRepository, workflowRunRepository, versionService, entityManager, null);
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
