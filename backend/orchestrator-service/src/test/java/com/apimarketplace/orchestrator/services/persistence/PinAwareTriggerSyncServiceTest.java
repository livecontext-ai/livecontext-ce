package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PinAwareTriggerSyncService - All Trigger Types Pin-Aware")
class PinAwareTriggerSyncServiceTest {

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

    // ─────────────────────── Helpers ───────────────────────

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

    private Map<String, Object> triggerMap(String id, String label, String type) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", id);
        t.put("label", label);
        t.put("type", type);
        t.put("trigger_type", type);
        return t;
    }

    private Map<String, Object> triggerMapWithParams(String id, String label, String type, Map<String, Object> params) {
        Map<String, Object> t = triggerMap(id, label, type);
        t.put("params", params);
        return t;
    }

    // ─────────────────────── syncAllTriggersFromPinnedVersion ───────────────────────

    @Nested
    @DisplayName("syncAllTriggersFromPinnedVersion - Full Pin-Aware Sync")
    class SyncAllFromPinnedTests {

        @Test
        @DisplayName("Pinned v2 with schedule+webhook+chat+form → syncs all from pinned plan")
        void syncsAllTriggerTypesFromPinnedPlan() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMapWithParams("sched-1", "Daily", "schedule",
                            Map.of("cron", "0 9 * * *", "timezone", "UTC", "enabled", true)),
                    triggerMap("wh-1", "My Webhook", "webhook"),
                    triggerMap("chat-1", "Support Chat", "chat"),
                    triggerMap("form-1", "Contact Form", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Schedule sync delegated
            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // Webhook orphan cleanup with current trigger IDs
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), argThat(ids -> ids.size() == 1));
            // Chat/form endpoint sync with trigger IDs from pinned plan
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("chat")));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("form")));
        }

        @Test
        @DisplayName("Pinned v2 with only manual trigger → cleans up webhook/chat/form")
        void pinnedManualCleansUpAllOtherTriggers() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("man-1", "Manual", "manual")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // No webhooks → cleanup with empty list
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), eq(List.of()));
            // No chat/form → set triggerId to null
            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }

        @Test
        @DisplayName("No pinned version → disables all triggers")
        void noPinnedVersionDisablesAllTriggers() {
            WorkflowEntity workflow = buildWorkflow(null);

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Schedule sync is called (ScheduleSyncService handles the null pinnedVersion case → disables)
            verify(scheduleSyncService).syncFromPinnedVersion(argThat(wf ->
                    wf.getId().equals(WORKFLOW_ID) && wf.getPinnedVersion() == null));
            // Webhook tokens cleaned up
            verify(triggerClient).cleanupOrphanTokens(WORKFLOW_ID, List.of());
            // Chat/form references cleared
            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }

        @Test
        @DisplayName("Pinned version unloadable → SKIP sync (no destructive cleanup) - symmetric to syncFromPlan plan-null guard (regression: 2026-04-30 hardening)")
        void pinnedVersionUnloadableSkipsInsteadOfCleanup() {
            // Pre-fix this called cleanupAllTriggers (delete schedules + clear chat/form
            // back-links). A transient DB error fetching the pinned version row would have
            // suspended a working production workflow. Now: skip and retry next sync.
            WorkflowEntity workflow = buildWorkflow(99);

            when(versionService.getVersion(WORKFLOW_ID, 99)).thenReturn(Optional.empty());

            service.syncAllTriggersFromPinnedVersion(workflow);

            // INVARIANT: NO destructive action when pinned plan unloadable
            verify(triggerClient, never()).cleanupOrphanTokens(any(), any());
            verify(triggerClient, never()).syncChatEndpointTriggerId(any(), any());
            verify(triggerClient, never()).syncFormEndpointTriggerId(any(), any());
            // scheduleSyncService.syncFromPinnedVersion also not called (no destructive
            // suspend either)
            org.mockito.Mockito.verifyNoInteractions(scheduleSyncService);
        }

        @Test
        @DisplayName("triggerClient null → no-op")
        void triggerClientNullNoOp() {
            PinAwareTriggerSyncService nullService = new PinAwareTriggerSyncService(null, versionService, scheduleSyncService, datasourceSubscriptionSyncService);

            // Should not throw
            nullService.syncAllTriggersFromPinnedVersion(buildWorkflow(2));

            verifyNoInteractions(versionService, scheduleSyncService);
        }
    }

    // ─────────────────────── syncAllTriggersFromPlan (draft path) ───────────────────────

    @Nested
    @DisplayName("syncAllTriggersFromPlan - Draft Save Path")
    class SyncAllFromPlanTests {

        @Test
        @DisplayName("Pinned workflow → ignores draft plan, syncs from pinned version")
        void pinnedWorkflowIgnoresDraftPlan() {
            WorkflowEntity workflow = buildWorkflow(2);

            // Draft plan has chat trigger with different ID
            Trigger draftChat = new Trigger("chat-draft", "Draft Chat", "chat", "chat");
            WorkflowPlan draftPlan = buildPlan(List.of(draftChat));

            // Pinned plan has different chat trigger
            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("chat-prod", "Prod Chat", "chat")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPlan(workflow, draftPlan);

            // Should use pinned, not draft
            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // Chat endpoint synced with prod trigger ID
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID),
                    argThat(id -> id != null && id.contains("prod_chat")));
        }

        @Test
        @DisplayName("Unpinned workflow → schedule sync follows pin state (disable); webhook/chat/form sync from draft")
        void unpinnedWorkflowSuspendsSchedulesAndSyncsUrlEndpointsFromDraft() {
            // Contract: scheduled_executions row is ACTIVE iff workflow.pinned_version IS NOT NULL.
            // Draft saves of unpinned workflows MUST NOT create or re-arm schedule rows -
            // they would auto-fire forever on workflows the user never made "live".
            // Webhook/chat/form endpoints (URL-bound, no auto-fire) sync from draft as before.
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger chatTrigger = new Trigger("chat-1", "My Chat", "chat", "chat");
            Trigger formTrigger = new Trigger("form-1", "My Form", "form", "form");
            Trigger webhookTrigger = new Trigger("wh-1", "My Hook", "webhook", "webhook");
            Trigger scheduleTrigger = new Trigger("sched-1", "My Schedule", "schedule", "schedule");
            WorkflowPlan plan = buildPlan(List.of(chatTrigger, formTrigger, webhookTrigger, scheduleTrigger));

            service.syncAllTriggersFromPlan(workflow, plan);

            // Schedule path: pin-aware sync (with null pinnedVersion → disableAllSchedules).
            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // Regression guard: the legacy draft-plan branch must never run on unpinned save.
            verify(scheduleSyncService, never()).syncFromPlan(any(), any());
            // Webhook cleanup with current trigger IDs (from draft)
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), argThat(ids -> ids.size() == 1));
            // Chat/form endpoint sync from draft (URL-bound, allowed pre-pin)
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Unpinned workflow with no chat/form → sets triggerIds to null")
        void unpinnedNoChatFormSetsNull() {
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger manualTrigger = new Trigger("man-1", "Manual", "manual", "manual");
            WorkflowPlan plan = buildPlan(List.of(manualTrigger));

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }

        @Test
        @DisplayName("Regression: unpinned save with schedule trigger in draft plan does NOT call legacy syncFromPlan (would create ACTIVE row)")
        void unpinnedSaveWithScheduleInDraftDoesNotArmRow() {
            // Bug 2026-05-14: 5 prod schedule rows ACTIVE on workflows where
            // pinned_version IS NULL - 4 of which were APPLICATION clones never
            // toggled live by the user. Root cause: the legacy "backward compat"
            // branch called scheduleSyncService.syncFromPlan on unpinned save,
            // which creates rows via syncSingleSchedule → createOrUpdateSchedule.
            // This regression guard pins the post-fix contract: pin-aware path
            // only - schedule row state strictly follows workflow.pinned_version.
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger scheduleTrigger = new Trigger("sched-1", "Daily", "schedule", "schedule");
            WorkflowPlan plan = buildPlan(List.of(scheduleTrigger));

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            verify(scheduleSyncService, never()).syncFromPlan(any(), any());
        }
    }

    // ─────────────────────── Webhook Sync ───────────────────────

    @Nested
    @DisplayName("Webhook Sync")
    class WebhookSyncTests {

        @Test
        @DisplayName("Pinned plan with webhook → cleanup orphans with current IDs")
        void pinnedWithWebhookCleansUpOrphans() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("wh-1", "Active Webhook", "webhook")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID),
                    argThat(ids -> ids.size() == 1 && ids.get(0).contains("webhook")));
        }

        @Test
        @DisplayName("Pinned plan without webhook → cleanup all tokens")
        void pinnedWithoutWebhookCleansUpAll() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("man-1", "Manual", "manual")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient).cleanupOrphanTokens(WORKFLOW_ID, List.of());
        }

        @Test
        @DisplayName("Webhook cleanup exception → doesn't block other sync")
        void webhookExceptionDoesntBlockOthers() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("wh-1", "Webhook", "webhook"),
                    triggerMap("chat-1", "Chat", "chat")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            doThrow(new RuntimeException("Network error")).when(triggerClient)
                    .cleanupOrphanTokens(any(), any());

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Chat/form sync still happens despite webhook error
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }
    }

    // ─────────────────────── Chat/Form Sync ───────────────────────

    @Nested
    @DisplayName("Chat/Form Endpoint Sync")
    class ChatFormSyncTests {

        @Test
        @DisplayName("Pinned plan with chat+form → syncs both trigger IDs")
        void pinnedWithChatAndForm() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("chat-1", "Support Chat", "chat"),
                    triggerMap("form-1", "Contact Form", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("chat")));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("form")));
        }

        @Test
        @DisplayName("Trigger type changed chat→manual in pinned plan → chat triggerId set to null")
        void chatRemovedSetsTriggerIdNull() {
            WorkflowEntity workflow = buildWorkflow(3);

            // v3 only has manual (chat was removed)
            Map<String, Object> v3Plan = planMapWithTriggers(List.of(
                    triggerMap("man-1", "Manual", "manual")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(buildVersionEntity(3, v3Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }

        @Test
        @DisplayName("Chat sync exception → doesn't block form sync")
        void chatExceptionDoesntBlockForm() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("chat-1", "Chat", "chat"),
                    triggerMap("form-1", "Form", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            doThrow(new RuntimeException("Chat sync error")).when(triggerClient)
                    .syncChatEndpointTriggerId(any(), any());

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Form sync still happens
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Multiple chat triggers → only first one used")
        void multipleChatTriggersUsesFirst() {
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger chat1 = new Trigger("chat-a", "Chat A", "chat", "chat");
            Trigger chat2 = new Trigger("chat-b", "Chat B", "chat", "chat");
            WorkflowPlan plan = buildPlan(List.of(chat1, chat2));

            service.syncAllTriggersFromPlan(workflow, plan);

            // Only the first chat trigger's normalized key is used
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID),
                    argThat(id -> id != null && id.contains("chat_a")));
        }

        @Test
        @DisplayName("Trigger params carry chatEndpointId → links endpoint to workflow before triggerId sync")
        void chatEndpointIdInParamsTriggersBackLink() {
            WorkflowEntity workflow = buildWorkflow(null);
            UUID chatEndpointId = UUID.randomUUID();

            Trigger chat = new Trigger("chat-1", "Chat", "chat", "chat",
                    Map.of("chatEndpointId", chatEndpointId.toString()));
            WorkflowPlan plan = buildPlan(List.of(chat));

            // Stub back-link to return non-null DTO (success); without it the strict
            // sync skips syncTriggerId to avoid clobbering unrelated endpoints.
            when(triggerClient.updateChatEndpointWorkflowReference(
                    eq(TENANT_ID), eq(chatEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).updateChatEndpointWorkflowReference(
                    eq(TENANT_ID), eq(chatEndpointId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Trigger params carry formEndpointId → links form endpoint to workflow")
        void formEndpointIdInParamsTriggersBackLink() {
            WorkflowEntity workflow = buildWorkflow(null);
            UUID formEndpointId = UUID.randomUUID();

            Trigger form = new Trigger("form-1", "Form", "form", "form",
                    Map.of("formEndpointId", formEndpointId.toString()));
            WorkflowPlan plan = buildPlan(List.of(form));

            when(triggerClient.updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Chat trigger without endpointId in params → no back-link call, only triggerId sync")
        void chatWithoutEndpointIdSkipsBackLink() {
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger chat = new Trigger("chat-1", "Chat", "chat", "chat");  // no params
            WorkflowPlan plan = buildPlan(List.of(chat));

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient, never()).updateChatEndpointWorkflowReference(any(), any(), any(), any());
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Back-link exception → SKIP triggerId sync (avoid clobbering unrelated endpoints already linked to workflow)")
        void backLinkExceptionSkipsTriggerIdSync() {
            // Updated 2026-04-29 hardening: previously syncTriggerId ran even after
            // back-link failure, but that could overwrite trigger_id on unrelated
            // endpoints already linked to this workflow_id. Now strict: failure → skip.
            WorkflowEntity workflow = buildWorkflow(null);
            UUID chatEndpointId = UUID.randomUUID();

            Trigger chat = new Trigger("chat-1", "Chat", "chat", "chat",
                    Map.of("chatEndpointId", chatEndpointId.toString()));
            WorkflowPlan plan = buildPlan(List.of(chat));

            when(triggerClient.updateChatEndpointWorkflowReference(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Network error"));

            // Should not throw
            service.syncAllTriggersFromPlan(workflow, plan);

            // INVARIANT: back-link failed → syncTriggerId MUST NOT run
            verify(triggerClient, never()).syncChatEndpointTriggerId(any(), any());
        }

        @Test
        @DisplayName("Plan with both chat AND form triggers → both back-links called with correct endpointIds")
        void bothChatAndFormEndpointIdsBackLinked() {
            WorkflowEntity workflow = buildWorkflow(null);
            UUID chatEndpointId = UUID.randomUUID();
            UUID formEndpointId = UUID.randomUUID();

            Trigger chat = new Trigger("chat-1", "Chat", "chat", "chat",
                    Map.of("chatEndpointId", chatEndpointId.toString()));
            Trigger form = new Trigger("form-1", "Form", "form", "form",
                    Map.of("formEndpointId", formEndpointId.toString()));
            WorkflowPlan plan = buildPlan(List.of(chat, form));

            // Stub both back-links to return non-null DTO (success)
            when(triggerClient.updateChatEndpointWorkflowReference(
                    eq(TENANT_ID), eq(chatEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto());
            when(triggerClient.updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).updateChatEndpointWorkflowReference(
                    eq(TENANT_ID), eq(chatEndpointId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }

        @Test
        @DisplayName("Malformed endpointId string in params → skipped silently, triggerId sync still runs")
        void malformedEndpointIdSkipped() {
            WorkflowEntity workflow = buildWorkflow(null);

            Trigger chat = new Trigger("chat-1", "Chat", "chat", "chat",
                    Map.of("chatEndpointId", "not-a-uuid"));
            WorkflowPlan plan = buildPlan(List.of(chat));

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient, never()).updateChatEndpointWorkflowReference(any(), any(), any(), any());
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
        }
    }

    // ─────────────────────── Draft Isolation (Cross-Trigger) ───────────────────────

    @Nested
    @DisplayName("Draft Isolation - Draft Save Never Touches Production Triggers")
    class DraftIsolationTests {

        @Test
        @DisplayName("v2 pinned (chat+schedule), v3 draft (form+webhook) → only v2 triggers active")
        void draftDoesNotChangeProductionTriggers() {
            WorkflowEntity workflow = buildWorkflow(2);

            // Draft plan has form + webhook (different from pinned)
            Trigger draftForm = new Trigger("form-draft", "Draft Form", "form", "form");
            Trigger draftWebhook = new Trigger("wh-draft", "Draft Hook", "webhook", "webhook");
            WorkflowPlan draftPlan = buildPlan(List.of(draftForm, draftWebhook));

            // Pinned plan v2 has chat + schedule
            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("chat-prod", "Prod Chat", "chat"),
                    triggerMapWithParams("sched-prod", "Prod Schedule", "schedule",
                            Map.of("cron", "0 9 * * *", "timezone", "UTC", "enabled", true))
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPlan(workflow, draftPlan);

            // Schedule sync uses pinned (via syncFromPinnedVersion)
            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // Webhook cleanup uses pinned plan (no webhooks → cleanup all)
            verify(triggerClient).cleanupOrphanTokens(WORKFLOW_ID, List.of());
            // Chat endpoint uses pinned plan's chat trigger
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID),
                    argThat(id -> id != null && id.contains("prod_chat")));
            // Form endpoint uses pinned plan (no form → null)
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }
    }

    // ─────────────────────── Pin/Unpin Lifecycle ───────────────────────

    @Nested
    @DisplayName("Pin/Unpin Lifecycle - All Trigger Types")
    class PinUnpinLifecycleTests {

        @Test
        @DisplayName("Pin v2 (all triggers) → unpin → all triggers disabled/cleaned up")
        void pinThenUnpinCleansUpAllTypes() {
            // Step 1: Pin v2 with all trigger types
            WorkflowEntity workflow = buildWorkflow(2);
            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMapWithParams("sched-1", "Schedule", "schedule",
                            Map.of("cron", "0 9 * * *", "timezone", "UTC", "enabled", true)),
                    triggerMap("wh-1", "Webhook", "webhook"),
                    triggerMap("chat-1", "Chat", "chat"),
                    triggerMap("form-1", "Form", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), argThat(ids -> ids.size() == 1));
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null));

            // Step 2: Unpin → all triggers are disabled/cleaned up
            reset(triggerClient, scheduleSyncService, versionService);
            workflow.setPinnedVersion(null);

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Schedule sync runs (ScheduleSyncService disables all when pinnedVersion=null)
            verify(scheduleSyncService).syncFromPinnedVersion(argThat(wf ->
                    wf.getId().equals(WORKFLOW_ID) && wf.getPinnedVersion() == null));
            // Webhook tokens cleaned up (empty list = remove all orphans)
            verify(triggerClient).cleanupOrphanTokens(WORKFLOW_ID, List.of());
            // Chat/form references cleared
            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(WORKFLOW_ID, null);
        }

        @Test
        @DisplayName("Switch pin: v2 (schedule+chat) → v3 (webhook+form) → correct triggers active")
        void switchPinUpdatesAllTriggers() {
            // Pin v3 (webhook+form)
            WorkflowEntity workflow = buildWorkflow(3);
            Map<String, Object> v3Plan = planMapWithTriggers(List.of(
                    triggerMap("wh-3", "Webhook V3", "webhook"),
                    triggerMap("form-3", "Form V3", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(buildVersionEntity(3, v3Plan)));

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Schedule sync runs (will find no schedules in pinned plan)
            verify(scheduleSyncService).syncFromPinnedVersion(workflow);
            // Webhook cleanup with v3's webhook IDs
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), argThat(ids -> ids.size() == 1));
            // Chat removed → null, form present → has value
            verify(triggerClient).syncChatEndpointTriggerId(WORKFLOW_ID, null);
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("form")));
        }
    }

    // ─────────────────────── Edge Cases ───────────────────────

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("All dependencies null → no-op")
        void allDependenciesNull() {
            PinAwareTriggerSyncService nullService = new PinAwareTriggerSyncService(null, null, null, null);

            // Should not throw
            nullService.syncAllTriggersFromPinnedVersion(buildWorkflow(2));
            nullService.syncAllTriggersFromPlan(buildWorkflow(null), null);
        }

        // Removed 2026-04-30: cleanupAllTriggers is no longer reachable from
        // syncAllTriggersFromPinnedVersion when loadPinnedPlan returns null -
        // we now skip-no-mutate (covered by pinnedVersionUnloadableSkipsInsteadOfCleanup
        // in SyncAllFromPinnedTests). The "individual failure tolerance" of cleanupAllTriggers
        // is still covered indirectly through unpin lifecycle tests.
    }

    // ─────────────────────── Webhook config push (V2) ───────────────────────

    @Nested
    @DisplayName("Webhook standalone config push - auth/method propagation")
    class WebhookConfigPushTests {

        @Test
        @DisplayName("Standalone webhook with basic auth in plan → pushes httpMethod + authType + authConfig to row")
        void pushesWebhookBasicAuthConfig() {
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);  // Unpinned: uses provided plan path
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "basic");
            params.put("basicUsername", "alice");
            params.put("basicPassword", "secret");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            when(triggerClient.updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneWebhookDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            org.mockito.ArgumentCaptor<com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest.class);
            verify(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), captor.capture());
            com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest req = captor.getValue();
            assert "POST".equals(req.httpMethod());
            assert "basic".equals(req.authType());
            assert req.authConfig() != null;
            assert "alice".equals(req.authConfig().get("username"));
            assert "secret".equals(req.authConfig().get("password"));
            assert WORKFLOW_ID.toString().equals(req.workflowId());
        }

        @Test
        @DisplayName("Standalone webhook with auth=none → authConfig is null (no creds leaked)")
        void pushesWebhookNoneAuthConfig() {
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "GET");
            params.put("authType", "none");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            when(triggerClient.updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneWebhookDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            org.mockito.ArgumentCaptor<com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest.class);
            verify(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), captor.capture());
            assert "GET".equals(captor.getValue().httpMethod());
            assert "none".equals(captor.getValue().authType());
            assert captor.getValue().authConfig() == null;
        }

        @Test
        @DisplayName("Webhook strict push 5xx → throws TriggerSyncWarningException so controller surfaces SECURITY warning to API response")
        void webhookTransient5xxSurfacesSecurityWarning() {
            // 2026-04-29 fail-loud hardening: a transient 5xx on auth/method push is a
            // SECURITY risk (public endpoint stays on stale auth_type). Must propagate
            // up so the API response carries triggerSyncWarning - user must see it.
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "basic");
            params.put("basicUsername", "alice");
            params.put("basicPassword", "secret");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            doThrow(new com.apimarketplace.trigger.client.TriggerClientException(
                    com.apimarketplace.trigger.client.TriggerClientException.Kind.SERVER_ERROR,
                    "503 Service Unavailable", null))
                    .when(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any());

            try {
                service.syncAllTriggersFromPlan(workflow, plan);
                throw new AssertionError("expected TriggerSyncWarningException");
            } catch (com.apimarketplace.orchestrator.services.persistence.TriggerSyncWarningException e) {
                assert e.getMessage().contains(webhookId.toString());
                assert e.getMessage().contains("SERVER_ERROR");
            }
        }

        @Test
        @DisplayName("Webhook strict push 404 → swallowed as warning log, no exception (phantom row, not a security risk)")
        void webhookNotFoundSilentlyLogged() {
            // 404 = standalone row was deleted out-of-band. NOT a security risk because
            // the row no longer accepts incoming webhooks. Just log warn and move on.
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "none");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            doThrow(new com.apimarketplace.trigger.client.TriggerClientException(
                    com.apimarketplace.trigger.client.TriggerClientException.Kind.NOT_FOUND,
                    "Webhook not found", null))
                    .when(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any());

            // Should NOT throw
            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), anyList());
        }

        @Test
        @DisplayName("Webhook strict push success → cleanupOrphanTokens still runs (happy path with strict client)")
        void webhookStrictSuccessRunsCleanup() {
            // The strict webhook path returns a DTO on success. Verifies the happy
            // path doesn't accidentally throw or skip downstream cleanup.
            UUID webhookId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "basic");
            params.put("basicUsername", "alice");
            params.put("basicPassword", "secret");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            when(triggerClient.updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneWebhookDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient).updateStandaloneWebhookStrict(eq(TENANT_ID), eq(webhookId), any());
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), anyList());
        }

        @Test
        @DisplayName("Webhook trigger without webhookId in params → no update call (legacy attached webhook)")
        void skipsLegacyAttachedWebhookWithoutWebhookId() {
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("httpMethod", "POST");
            params.put("authType", "none");
            Trigger trigger = new Trigger("hook", "Hook", "webhook", "webhook", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            service.syncAllTriggersFromPlan(workflow, plan);

            // Legacy attached webhooks: no row to update, only token cleanup
            verify(triggerClient, never()).updateStandaloneWebhookStrict(any(), any(), any());
            verify(triggerClient).cleanupOrphanTokens(eq(WORKFLOW_ID), anyList());
        }
    }

    // ─────────────────────── Form config push (V2) ───────────────────────

    @Nested
    @DisplayName("Form endpoint config push - fields/successMessage propagation")
    class FormConfigPushTests {

        @Test
        @DisplayName("Form trigger with formEndpointId in plan → pushes fields + successMessage to row")
        void pushesFormConfig() {
            UUID formEndpointId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> field1 = Map.of("name", "email", "type", "email", "required", true);
            Map<String, Object> field2 = Map.of("name", "message", "type", "text", "required", false);
            Map<String, Object> params = new HashMap<>();
            params.put("formEndpointId", formEndpointId.toString());
            params.put("formTitle", "Contact Us");
            params.put("formDescription", "Get in touch");
            params.put("submitButtonText", "Send");
            params.put("fields", List.of(field1, field2));
            Trigger trigger = new Trigger("form", "Form", "form", "form", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            // Back-link must succeed for config push to run (strict invariant 2026-04-29)
            when(triggerClient.updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());
            when(triggerClient.updateFormEndpoint(eq(TENANT_ID), eq(formEndpointId), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());

            service.syncAllTriggersFromPlan(workflow, plan);

            org.mockito.ArgumentCaptor<com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest.class);
            verify(triggerClient).updateFormEndpoint(eq(TENANT_ID), eq(formEndpointId), captor.capture());
            com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest req = captor.getValue();
            assert "Get in touch".equals(req.description());
            assert "Send".equals(req.successMessage());
            assert req.formConfig().size() == 2;
            assert WORKFLOW_ID.toString().equals(req.workflowId());
        }

        @Test
        @DisplayName("Form back-link returns null → skip syncTriggerId AND config push (avoid clobbering unrelated endpoints)")
        void formBackLinkNullSkipsSubsequentSync() {
            // INVARIANT: if updateFormEndpointWorkflowReference fails silently (returns
            // null on cross-tenant 404 or transient error), running syncFormEndpointTriggerId
            // afterwards would overwrite the triggerId on whatever endpoints DID match
            // workflow_id - incoherent state. The fix skips both syncTriggerId and
            // pushFormEndpointConfig in this case.
            UUID formEndpointId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("formEndpointId", formEndpointId.toString());
            params.put("fields", List.of());
            Trigger trigger = new Trigger("form", "Form", "form", "form", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            // Back-link returns null (silent fail)
            when(triggerClient.updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(formEndpointId), eq(WORKFLOW_ID), any()))
                    .thenReturn(null);

            service.syncAllTriggersFromPlan(workflow, plan);

            // Both subsequent calls MUST be skipped
            verify(triggerClient, never()).syncFormEndpointTriggerId(any(), any());
            verify(triggerClient, never()).updateFormEndpoint(any(), any(), any());
        }

        @Test
        @DisplayName("Form trigger without formEndpointId → only triggerId sync, no config push")
        void skipsFormConfigPushWhenNoEndpointId() {
            WorkflowEntity workflow = buildWorkflow(null);
            Map<String, Object> params = new HashMap<>();
            params.put("fields", List.of());
            Trigger trigger = new Trigger("form", "Form", "form", "form", params);
            WorkflowPlan plan = buildPlan(List.of(trigger));

            service.syncAllTriggersFromPlan(workflow, plan);

            verify(triggerClient, never()).updateFormEndpoint(any(), any(), any());
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), eq("trigger:form"));
        }
    }

    // ─────────────────────── Acquired application form auto-create (stripped clone) ───────────────────────

    @Nested
    @DisplayName("Acquired application form endpoint - auto-create on pin so it reaches Public Access")
    class AcquiredFormEndpointCreationTests {

        private WorkflowEntity buildWorkflowWithOrg(Integer pinnedVersion, String orgId) {
            WorkflowEntity wf = buildWorkflow(pinnedVersion);
            wf.setOrganizationId(orgId);
            return wf;
        }

        @Test
        @DisplayName("Form trigger with NO formEndpointId (stripped clone) → creates a fresh org-scoped endpoint, back-links it, syncs triggerId")
        void createsFreshFormEndpointForStrippedClone() {
            // Acquire strips formEndpointId (PlanStripUtils), so the pinned plan's form trigger has
            // no endpoint id. Pre-fix: nothing created → form absent from Public Access + cannot fire.
            WorkflowEntity workflow = buildWorkflowWithOrg(2, "org-acme");
            UUID newFormId = UUID.randomUUID();

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("form-1", "Contact Form", "form")  // no params → formEndpointId stripped
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            // No endpoint linked to this workflow yet → must create one.
            when(triggerClient.getFormEndpoints(TENANT_ID)).thenReturn(Collections.emptyList());
            com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto created =
                    new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto();
            created.setId(newFormId);
            when(triggerClient.createFormEndpoint(eq(TENANT_ID), isNull(), any(), eq("org-acme")))
                    .thenReturn(created);
            when(triggerClient.updateFormEndpointWorkflowReference(eq(TENANT_ID), eq(newFormId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Created in the acquirer's WORKSPACE (org threaded), then back-linked + triggerId synced
            // → the form now shows in Public Access and can fire.
            verify(triggerClient).createFormEndpoint(eq(TENANT_ID), isNull(), any(), eq("org-acme"));
            verify(triggerClient).updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(newFormId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("form")));
        }

        @Test
        @DisplayName("Re-pin with an endpoint already linked to this workflow → reuses it, never creates a duplicate")
        void reusesExistingEndpointOnRepinNoDuplicate() {
            WorkflowEntity workflow = buildWorkflowWithOrg(2, "org-acme");
            UUID existingFormId = UUID.randomUUID();

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("form-1", "Contact Form", "form")
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            // A prior sync already created + linked an endpoint to this workflow.
            com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto existing =
                    new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto();
            existing.setId(existingFormId);
            existing.setWorkflowId(WORKFLOW_ID);
            when(triggerClient.getFormEndpoints(TENANT_ID)).thenReturn(List.of(existing));
            when(triggerClient.updateFormEndpointWorkflowReference(eq(TENANT_ID), eq(existingFormId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto());

            service.syncAllTriggersFromPinnedVersion(workflow);

            // Idempotent: reused the workflow-linked endpoint, no second row created.
            verify(triggerClient, never()).createFormEndpoint(any(), any(), any(), any());
            verify(triggerClient).updateFormEndpointWorkflowReference(
                    eq(TENANT_ID), eq(existingFormId), eq(WORKFLOW_ID), any());
            verify(triggerClient).syncFormEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("form")));
        }

        @Test
        @DisplayName("Chat trigger with NO chatEndpointId (stripped clone) → creates a fresh org-scoped chat endpoint + back-links it")
        void createsFreshChatEndpointForStrippedClone() {
            // Same stripped-clone gap as form, on the chat public endpoint.
            WorkflowEntity workflow = buildWorkflowWithOrg(2, "org-acme");
            UUID newChatId = UUID.randomUUID();

            Map<String, Object> v2Plan = planMapWithTriggers(List.of(
                    triggerMap("chat-1", "Support Chat", "chat")  // no params → chatEndpointId stripped
            ));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.getChatEndpoints(TENANT_ID)).thenReturn(Collections.emptyList());
            com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto created =
                    new com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto();
            created.setId(newChatId);
            when(triggerClient.createChatEndpoint(eq(TENANT_ID), isNull(), any(), eq("org-acme")))
                    .thenReturn(created);
            when(triggerClient.updateChatEndpointWorkflowReference(eq(TENANT_ID), eq(newChatId), eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto());

            service.syncAllTriggersFromPinnedVersion(workflow);

            verify(triggerClient).createChatEndpoint(eq(TENANT_ID), isNull(), any(), eq("org-acme"));
            verify(triggerClient).updateChatEndpointWorkflowReference(
                    eq(TENANT_ID), eq(newChatId), eq(WORKFLOW_ID), eq("Test Workflow"));
            verify(triggerClient).syncChatEndpointTriggerId(eq(WORKFLOW_ID), argThat(id -> id != null && id.contains("chat")));
        }
    }
}
