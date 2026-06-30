package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.services.persistence.ScheduleSyncService;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link WorkflowResumeService#reactivateScheduleTriggers}.
 *
 * <p>Bug context (2026-05-07): a user reactivated a pinned workflow whose
 * schedule rows had been deleted upstream (orphan reaper / clone-time edge
 * case). The reactivate call invoked {@code triggerClient.enableSchedulesByWorkflow}
 * which silently no-op'd on an empty list. Symptom: workflow stayed inert
 * despite being pinned; the "Re-enabled schedules" log lied.
 *
 * <p>Audit follow-up (F1 in the audit): the initial fix used
 * {@code armed == 0} as the predicate to trigger the fallback. That missed
 * the case where the plan declares N schedule triggers but only M&lt;N rows
 * exist (rows for some triggers were reaped while others survived). The
 * reconcile would have been skipped and the missing rows stayed silently
 * absent. The current contract: always reconcile from the pinned plan when
 * the workflow is pinned, regardless of armed count - V60 unique constraint
 * makes this idempotent. Each test pins one branch of that contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService.reactivateScheduleTriggers - schedule re-arm + always-reconcile-if-pinned")
class WorkflowResumeServiceReactivateTriggersTest {

    @Mock private TriggerClient triggerClient;
    @Mock private PinAwareTriggerSyncService pinAwareTriggerSyncService;
    @Mock private ScheduleSyncService scheduleSyncService;

    private WorkflowResumeService service;
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The full constructor needs ~12 collaborators we don't exercise here.
        // Wire only the fields the helper uses, post-construction. This keeps
        // the test focused on the helper's contract rather than the surrounding
        // reactivate orchestration.
        service = new WorkflowResumeService(
                null, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "triggerClient", triggerClient);
        ReflectionTestUtils.setField(service, "pinAwareTriggerSyncService", pinAwareTriggerSyncService);
        ReflectionTestUtils.setField(service, "scheduleSyncService", scheduleSyncService);
    }

    @Test
    @DisplayName("Pinned + rows aligned with expected (3 of 3) → reconcile no-op INFO")
    void pinnedRowsAlignedWithExpected() {
        // Audit F1: even when rows > 0, we MUST reconcile - V60 makes it
        // idempotent. Cross-check post-reconcile against expected count from
        // the pinned plan: 3 rows pre + 3 rows post + 3 expected = aligned.
        WorkflowEntity wf = pinnedWorkflow(7);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(3, 3);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.of(3));

        service.reactivateScheduleTriggers(wf);

        InOrder order = inOrder(triggerClient, pinAwareTriggerSyncService);
        order.verify(triggerClient).enableSchedulesByWorkflow(workflowId);     // initial arm
        order.verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        order.verify(triggerClient).enableSchedulesByWorkflow(workflowId);     // post-sync verification
    }

    @Test
    @DisplayName("Pinned + 0 rows initially + reconcile recreates all expected → WARN reconciled")
    void pinnedZeroRowsRecoversViaReconcile() {
        // Bug case being fixed: rows missing pre-reconcile, sync recreates
        // all 2 expected, post-arm finds 2. Healthy recovery.
        WorkflowEntity wf = pinnedWorkflow(17);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(0, 2);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.of(2));

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("Pinned + sync silently dropped triggers (expected 2, got 1) → ERROR partial-failure")
    void pinnedSilentPartialFailureIsErrorLogged() {
        // Round-3 audit P1 (audit #2 F2 + #3 F1+F2+F8): without expected
        // count, "0 → 1 row recreated" looks like a happy WARN. With the
        // pinned plan declaring 2, this is a SILENT PARTIAL FAILURE - sync
        // recreated 1 but swallowed an exception on the second (transient
        // 503 caught by TriggerClient.createOrUpdateSchedule's generic catch).
        // The user's workflow will fire only the surviving schedule until
        // someone investigates. Must surface as ERROR, not WARN.
        WorkflowEntity wf = pinnedWorkflow(17);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(0, 1);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.of(2));

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
        // Helper does NOT throw - outer reactivate flow continues.
    }

    @Test
    @DisplayName("Pinned + total-failure (expected 2, got 0 post-reconcile) → ERROR distinct from partial")
    void pinnedTotalRecreateFailureIsErrorLogged() {
        // expected > 0 && actual == 0: sync silently failed for ALL
        // triggers. Different log message from the partial case so operators
        // can triage faster (likely trigger-service down vs. quota issue).
        WorkflowEntity wf = pinnedWorkflow(17);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(0, 0);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.of(2));

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("Pinned + plan declares 0 schedule triggers (webhook-only) → INFO, NOT ERROR")
    void pinnedExpectedZeroIsExpectedForWebhookOnlyWorkflows() {
        // Round-2 audit fix (audit #2/#3): the original code logged ERROR
        // "pinned plan may be corrupt" when a webhook-only workflow was
        // reactivated (because schedule rows are 0 by design). With the
        // expected-count plumb-through, the helper now KNOWS the plan
        // declares 0 schedule triggers and logs INFO instead. No false
        // alarm in monitoring.
        WorkflowEntity wf = pinnedWorkflow(17);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(0, 0);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.of(0));

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("Pinned + expected count unavailable (plan unloadable) → fallback heuristic uses row-delta")
    void pinnedExpectedUnavailableFallsBackToHeuristic() {
        // Defensive: if scheduleSyncService can't load the pinned plan
        // (corrupt blob, version row deleted), it returns OptionalInt.empty().
        // The helper falls back to the weaker row-delta heuristic - better
        // than throwing, but logs reflect lower confidence ("expected count
        // unavailable").
        WorkflowEntity wf = pinnedWorkflow(17);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(2, 3);
        when(scheduleSyncService.countScheduleTriggersInPinnedPlan(wf)).thenReturn(OptionalInt.empty());

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("Pinned + scheduleSyncService bean absent → fallback heuristic, no NPE")
    void pinnedScheduleSyncServiceAbsentFallsBackToHeuristic() {
        // The bean is @Autowired(required = false). If absent in some
        // deployment, the helper must not NPE - it falls back to the
        // row-delta heuristic just like when expected is unavailable.
        ReflectionTestUtils.setField(service, "scheduleSyncService", null);
        WorkflowEntity wf = pinnedWorkflow(5);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(1, 1);

        service.reactivateScheduleTriggers(wf);

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        verify(triggerClient, times(2)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("Unpinned workflow → no arm, no reconcile (triggers active iff pinned)")
    void unpinnedWorkflowDoesNotReArmSchedules() {
        // Regression guard for bug 2026-05-14: previously reactivateScheduleTriggers
        // called enableSchedulesByWorkflow unconditionally - for an unpinned workflow
        // with SUSPENDED_UNPINNED rows, that flipped them back to ACTIVE, defeating
        // the pin-state contract enforced by PinAwareTriggerSyncService /
        // ScheduleSyncService. Post-fix: unpinned workflows skip the arm entirely.
        WorkflowEntity wf = unpinnedWorkflow();

        service.reactivateScheduleTriggers(wf);

        verify(triggerClient, never()).enableSchedulesByWorkflow(workflowId);
        verifyNoInteractions(pinAwareTriggerSyncService);
    }

    @Test
    @DisplayName("Pinned + sync service bean absent → arm-only, ERROR-logged, no NPE")
    void pinnedButSyncServiceAbsentArmsOnly() {
        // Audit F3: this is a deployment misconfiguration. The helper must
        // arm what it can and surface the missing bean as ERROR. It must not
        // NPE and must not pretend the reconcile happened.
        ReflectionTestUtils.setField(service, "pinAwareTriggerSyncService", null);
        WorkflowEntity wf = pinnedWorkflow(2);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(1);

        service.reactivateScheduleTriggers(wf);

        verify(triggerClient, times(1)).enableSchedulesByWorkflow(workflowId);
    }

    @Test
    @DisplayName("triggerClient bean missing → method returns silently, no NullPointerException")
    void absentTriggerClientShortCircuits() {
        // Mirrors the @Autowired(required = false) contract on the field.
        ReflectionTestUtils.setField(service, "triggerClient", null);
        WorkflowEntity wf = pinnedWorkflow(1);

        service.reactivateScheduleTriggers(wf);

        verifyNoInteractions(pinAwareTriggerSyncService);
    }

    @Test
    @DisplayName("Reconcile throws → exception propagates (caught by outer wrapper in reactivateWorkflow)")
    void reconcileThrowsPropagates() {
        // Audit F4: if syncAllTriggersFromPinnedVersion throws (e.g. unhealthy
        // trigger-service, broken pinned-plan blob deserialisation), the helper
        // must propagate. The CALLER (reactivateWorkflow at line 622) wraps
        // this whole helper in a try/catch that logs and continues with the
        // rest of reactivate. Verifying propagation here keeps the call-site
        // contract honest - if a future refactor adds a swallowing catch
        // inside the helper, this test breaks and forces a discussion.
        WorkflowEntity wf = pinnedWorkflow(5);
        when(triggerClient.enableSchedulesByWorkflow(workflowId)).thenReturn(0);
        doThrow(new RuntimeException("trigger-service 503"))
                .when(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);

        assertThatThrownBy(() -> service.reactivateScheduleTriggers(wf))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("trigger-service 503");

        verify(pinAwareTriggerSyncService).syncAllTriggersFromPinnedVersion(wf);
        // Post-sync verification arm MUST NOT happen when sync throws.
        verify(triggerClient, times(1)).enableSchedulesByWorkflow(workflowId);
    }

    // ---- helpers ----

    private WorkflowEntity pinnedWorkflow(int pinnedVersion) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(workflowId);
        w.setPinnedVersion(pinnedVersion);
        return w;
    }

    private WorkflowEntity unpinnedWorkflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(workflowId);
        w.setPinnedVersion(null);
        return w;
    }
}
