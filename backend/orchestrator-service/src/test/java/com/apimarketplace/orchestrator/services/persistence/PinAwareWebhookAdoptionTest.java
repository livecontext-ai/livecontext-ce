package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Surface: trigger:webhook - standalone-webhook ADOPTION onto its workflow at pin/save.
 *
 * <p><b>Bug class under test (the "schedule-style orphan").</b> A standalone trigger created
 * in the builder is born with {@code workflow_id} NULL. For the schedule surface, the F4
 * PUB-HIJACK fix had removed the adoption call, so the row was never linked → never fired,
 * was never counted toward the plan limit, and was reaped after 24h. The fix re-introduced a
 * guarded NULL→value adoption ({@code ScheduleSyncService.syncSingleSchedule} →
 * {@code updateScheduleWorkflowReferenceStrict}).
 *
 * <p>The webhook surface adopts through a DIFFERENT mechanism: there is no separate
 * {@code trigger_id} column on {@code standalone_webhooks} and the fire path
 * ({@code WebhookDispatchService.dispatchStandalone}) matches the token against the
 * workflow plan, NOT against the row's {@code workflow_id}. Adoption happens inside the
 * existing per-trigger config push: {@link PinAwareTriggerSyncService#syncWebhooks} →
 * {@code pushStandaloneWebhookConfig} → {@code triggerClient.updateStandaloneWebhookStrict}
 * with {@code request.workflowId() == workflow.getId()}, landing in
 * {@code StandaloneWebhookService.update} which sets {@code workflow_id} (NULL→value,
 * guarded by {@code assertWorkflowReferenceMutationAllowed}).
 *
 * <p>This test pins the register→pin→adopt contract on BOTH the pinned-version sync path
 * ({@link PinAwareTriggerSyncService#syncAllTriggersFromPinnedVersion}, exercised when a
 * production version is pinned) AND the draft/save path
 * ({@link PinAwareTriggerSyncService#syncAllTriggersFromPlan}). The pinned path is the one
 * the existing {@code PinAwareTriggerSyncServiceTest.WebhookConfigPushTests} never covers -
 * those tests all run on {@code buildWorkflow(null)} (draft) with no {@code webhookId} in the
 * pinned-plan triggers, so a regression that dropped the webhook config push from the pinned
 * branch (the exact shape of the schedule F4 regression) would go unnoticed there.
 *
 * <p>Pre-fix behaviour these tests would catch (RED): if {@code syncWebhooks}/
 * {@code pushStandaloneWebhookConfig} stopped pushing the workflow reference at pin
 * (orphan: workflow_id stays NULL), {@code updateStandaloneWebhookStrict} is either never
 * called or called with a null/blank {@code workflowId} - both assertions below fail.
 * Post-fix (GREEN): the row is adopted with exactly {@code WORKFLOW_ID}.
 *
 * <p>Harness mirrors {@link PinAwareTriggerSyncServiceTest} exactly (same mocks, same
 * {@code build*}/{@code triggerMap*} helpers, same {@code WorkflowPlan} constructor).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("trigger:webhook - Standalone Webhook Adoption at Pin/Save (orphan-bug guard)")
class PinAwareWebhookAdoptionTest {

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private WorkflowPlanVersionService versionService;

    @Mock
    private ScheduleSyncService scheduleSyncService;

    @Mock
    private DatasourceSubscriptionSyncService datasourceSubscriptionSyncService;

    private PinAwareTriggerSyncService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "user-test";

    @BeforeEach
    void setUp() {
        service = new PinAwareTriggerSyncService(triggerClient, versionService, scheduleSyncService,
                datasourceSubscriptionSyncService);
    }

    // ─────────────────────── Helpers (mirror PinAwareTriggerSyncServiceTest) ───────────────────────

    private WorkflowEntity buildWorkflow(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(TENANT_ID);
        wf.setName("Test Workflow");
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowPlan buildPlan(List<Trigger> triggers) {
        return new WorkflowPlan(null, TENANT_ID, triggers,
                null, null, null, null, null, null, null, null);
    }

    private WorkflowPlanVersionEntity buildVersionEntity(int version, Map<String, Object> planMap) {
        return new WorkflowPlanVersionEntity(WORKFLOW_ID, version, planMap, TENANT_ID);
    }

    private Map<String, Object> planMapWithTriggers(List<Map<String, Object>> triggers) {
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("triggers", triggers);
        planMap.put("name", "Test Workflow");
        return planMap;
    }

    private Map<String, Object> webhookTriggerMap(String id, String label, UUID webhookId) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", id);
        t.put("label", label);
        t.put("type", "webhook");
        t.put("trigger_type", "webhook");
        Map<String, Object> params = new HashMap<>();
        params.put("webhookId", webhookId.toString());
        params.put("httpMethod", "POST");
        params.put("authType", "none");
        t.put("params", params);
        return t;
    }

    private StandaloneWebhookRequest stubOk() {
        StandaloneWebhookDto dto = new StandaloneWebhookDto();
        when(triggerClient.updateStandaloneWebhookStrict(eq(TENANT_ID), any(UUID.class), any()))
                .thenReturn(dto);
        return null; // sentinel; we only need the stub side-effect
    }

    // ─────────────────────── Pinned path: register → pin → adopt ───────────────────────

    @Nested
    @DisplayName("syncAllTriggersFromPinnedVersion - adopts standalone webhook (workflow_id NULL→value)")
    class PinnedAdoption {

        @Test
        @DisplayName("Pinned plan with a standalone webhook (webhookId in params) → pushes workflowId = the workflow's id (adoption)")
        void pinnedPlanAdoptsStandaloneWebhookOntoWorkflow() {
            // REGISTER: a standalone webhook was created in the builder → its UUID is referenced
            // by the trigger's params.webhookId. The row itself has workflow_id NULL until adopted.
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(7); // PINNED - production sync path

            Map<String, Object> pinnedPlan = planMapWithTriggers(List.of(
                    webhookTriggerMap("hook", "My Webhook", webhookId)
            ));
            when(versionService.getVersion(WORKFLOW_ID, 7))
                    .thenReturn(Optional.of(buildVersionEntity(7, pinnedPlan)));
            stubOk();

            // PIN: sync from the pinned production version.
            service.syncAllTriggersFromPinnedVersion(workflow);

            // ADOPT: the config push must carry workflowId = the workflow's id. This is the
            // NULL→value link that takes the row off the orphan list (counted + not reaped).
            // Pre-fix shape (schedule F4): no push from the pinned branch → this verify fails.
            ArgumentCaptor<StandaloneWebhookRequest> captor =
                    ArgumentCaptor.forClass(StandaloneWebhookRequest.class);
            verify(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), captor.capture());
            assertEquals(WORKFLOW_ID.toString(), captor.getValue().workflowId(),
                    "standalone webhook must be adopted onto its workflow at pin (workflow_id NULL→value)");
            assertEquals("Test Workflow", captor.getValue().workflowName());
        }

        @Test
        @DisplayName("Pinned plan webhook WITHOUT webhookId (legacy attached) → no adoption push (token-only)")
        void pinnedLegacyAttachedWebhookNotAdopted() {
            // A legacy attached webhook has no standalone row to adopt - only token cleanup runs.
            // Guards against a future change that blindly pushes for every webhook trigger.
            WorkflowEntity workflow = buildWorkflow(7);

            Map<String, Object> t = new HashMap<>();
            t.put("id", "hook");
            t.put("label", "Legacy Hook");
            t.put("type", "webhook");
            t.put("trigger_type", "webhook");
            t.put("params", Map.of("httpMethod", "POST", "authType", "none")); // no webhookId
            Map<String, Object> pinnedPlan = planMapWithTriggers(List.of(t));
            when(versionService.getVersion(WORKFLOW_ID, 7))
                    .thenReturn(Optional.of(buildVersionEntity(7, pinnedPlan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient, never()).updateStandaloneWebhookStrict(any(), any(), any());
            // Token cleanup still runs for the (single) webhook trigger id.
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), any());
        }
    }

    // ─────────────────────── Draft/save path: configure-before-pin also adopts ───────────────────────

    @Nested
    @DisplayName("syncAllTriggersFromPlan - adopts standalone webhook on draft save (URL-bound, allowed pre-pin)")
    class DraftAdoption {

        @Test
        @DisplayName("Unpinned save with a standalone webhook in the draft plan → pushes workflowId = the workflow's id")
        void draftSaveAdoptsStandaloneWebhook() {
            // Webhook/chat/form endpoints are URL-bound and may be configured before pinning,
            // so their sync runs from the draft plan too. Adoption must still set workflow_id.
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null); // UNPINNED - draft save path

            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "none");
            Trigger webhook = new Trigger("hook", "My Webhook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(webhook));
            stubOk();

            service.syncAllTriggersFromPlan(workflow, plan);

            ArgumentCaptor<StandaloneWebhookRequest> captor =
                    ArgumentCaptor.forClass(StandaloneWebhookRequest.class);
            verify(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), captor.capture());
            assertEquals(WORKFLOW_ID.toString(), captor.getValue().workflowId(),
                    "standalone webhook must be adopted onto its workflow on draft save (workflow_id NULL→value)");
        }
    }
}
