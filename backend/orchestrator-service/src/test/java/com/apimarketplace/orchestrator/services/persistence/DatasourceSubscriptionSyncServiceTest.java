package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.DatasourceSubscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pin-aware sync for datasource trigger subscriptions.
 *
 * <p>Mirrors {@link ScheduleSyncServiceTest} but for the event-driven datasource
 * trigger: only the pinned (production) plan version's subscriptions are
 * registered in trigger-service. Draft / unpinned version saves never affect
 * production subscriptions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatasourceSubscriptionSyncService - Pin-Aware Subscription Lifecycle")
class DatasourceSubscriptionSyncServiceTest {

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private WorkflowPlanVersionService versionService;

    private DatasourceSubscriptionSyncService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "user-test";

    @BeforeEach
    void setUp() {
        service = new DatasourceSubscriptionSyncService(triggerClient, versionService);
    }

    // ──────────────────── Helpers ────────────────────

    private WorkflowEntity workflow(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(TENANT_ID);
        wf.setName("Test Workflow");
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private Map<String, Object> datasourceTriggerMap(String triggerLabel, long dsId,
                                                     List<String> eventTypes,
                                                     Map<String, Object> filter) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("id", String.valueOf(dsId));
        trigger.put("label", triggerLabel);
        trigger.put("type", "datasource");
        trigger.put("trigger_type", "datasource");
        Map<String, Object> params = new HashMap<>();
        params.put("datasource_id", dsId);
        if (eventTypes != null) params.put("event_types", eventTypes);
        if (filter != null) params.put("filter", filter);
        trigger.put("params", params);
        return trigger;
    }

    private Map<String, Object> planMapWithDatasourceTrigger(String triggerLabel, long dsId,
                                                             List<String> eventTypes,
                                                             Map<String, Object> filter) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", "Test Workflow");
        plan.put("triggers", List.of(datasourceTriggerMap(triggerLabel, dsId, eventTypes, filter)));
        return plan;
    }

    private WorkflowPlanVersionEntity versionEntity(int version, Map<String, Object> planMap) {
        return new WorkflowPlanVersionEntity(WORKFLOW_ID, version, planMap, TENANT_ID);
    }

    // ──────────────────── syncFromPinnedVersion ────────────────────

    @Nested
    @DisplayName("syncFromPinnedVersion")
    class SyncFromPinned {

        @Test
        @DisplayName("Unpinned workflow → all subscriptions deleted, no upserts")
        void unpinnedDeletesAll() {
            service.syncFromPinnedVersion(workflow(null));

            verify(triggerClient).deleteDatasourceSubscriptionsByWorkflow(WORKFLOW_ID);
            verify(triggerClient, never()).createOrUpdateDatasourceSubscription(any());
            verify(triggerClient, never()).pruneDatasourceSubscriptions(any(), anyList());
        }

        @Test
        @DisplayName("Pinned v2 with datasource trigger → upsert with v2 event_types and filter")
        void pinnedVersionUpsertsFromThatPlan() {
            WorkflowEntity wf = workflow(2);
            Map<String, Object> v2Plan = planMapWithDatasourceTrigger(
                    "On Order Change", 42L,
                    List.of("row_created", "row_updated"),
                    Map.of("column", "status", "operator", "=", "value", "paid"));
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionEntity(2, v2Plan)));

            service.syncFromPinnedVersion(wf);

            ArgumentCaptor<DatasourceSubscriptionRequest> captor =
                    ArgumentCaptor.forClass(DatasourceSubscriptionRequest.class);
            verify(triggerClient).createOrUpdateDatasourceSubscription(captor.capture());
            DatasourceSubscriptionRequest req = captor.getValue();
            assertThat(req.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(req.getPlanVersion()).isEqualTo(2);
            assertThat(req.getDataSourceId()).isEqualTo(42L);
            assertThat(req.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(req.getEventTypes())
                    .containsExactlyInAnyOrder("row_created", "row_updated");
            assertThat(req.getFilter())
                    .containsEntry("column", "status")
                    .containsEntry("operator", "=")
                    .containsEntry("value", "paid");
        }

        @Test
        @DisplayName("Pinned plan prunes orphan subscriptions - current trigger ids passed to prune")
        void prunesOrphans() {
            WorkflowEntity wf = workflow(2);
            Map<String, Object> plan = planMapWithDatasourceTrigger(
                    "Survivor", 42L, List.of("row_created"), null);
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(versionEntity(2, plan)));

            service.syncFromPinnedVersion(wf);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
            verify(triggerClient).pruneDatasourceSubscriptions(eq(WORKFLOW_ID), captor.capture());
            assertThat(captor.getValue()).containsExactly("trigger:survivor");
        }

        @Test
        @DisplayName("Pinned version missing → falls back to deleting all subscriptions")
        void pinnedVersionMissing() {
            WorkflowEntity wf = workflow(7);
            when(versionService.getVersion(WORKFLOW_ID, 7)).thenReturn(Optional.empty());

            service.syncFromPinnedVersion(wf);

            verify(triggerClient).deleteDatasourceSubscriptionsByWorkflow(WORKFLOW_ID);
            verify(triggerClient, never()).createOrUpdateDatasourceSubscription(any());
        }

        @Test
        @DisplayName("Pinned plan with no datasource trigger → prune to empty (removes all orphans)")
        void pinnedPlanWithoutDatasourceTrigger() {
            WorkflowEntity wf = workflow(3);
            Map<String, Object> plan = new HashMap<>();
            plan.put("name", "No Datasource");
            // webhook-only plan, no datasource triggers
            Map<String, Object> webhook = new HashMap<>();
            webhook.put("id", "wh-1");
            webhook.put("label", "Hook");
            webhook.put("type", "webhook");
            webhook.put("trigger_type", "webhook");
            plan.put("triggers", List.of(webhook));
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(versionEntity(3, plan)));

            service.syncFromPinnedVersion(wf);

            verify(triggerClient, never()).createOrUpdateDatasourceSubscription(any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
            verify(triggerClient).pruneDatasourceSubscriptions(eq(WORKFLOW_ID), captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("Datasource trigger missing datasource_id is skipped (no upsert), still pruned")
        void triggerMissingDatasourceIdSkipped() {
            WorkflowEntity wf = workflow(1);
            Map<String, Object> plan = new HashMap<>();
            Map<String, Object> triggerMap = new HashMap<>();
            // No id, no datasource_id, no table_id, no numeric content
            triggerMap.put("id", "not-a-number");
            triggerMap.put("label", "Broken");
            triggerMap.put("type", "datasource");
            triggerMap.put("params", new HashMap<>());
            plan.put("triggers", List.of(triggerMap));
            when(versionService.getVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(versionEntity(1, plan)));

            service.syncFromPinnedVersion(wf);

            verify(triggerClient, never()).createOrUpdateDatasourceSubscription(any());
            verify(triggerClient).pruneDatasourceSubscriptions(eq(WORKFLOW_ID), anyList());
        }
    }

    // ──────────────────── deleteAllSubscriptions ────────────────────

    @Nested
    @DisplayName("deleteAllSubscriptions")
    class DeleteAll {

        @Test
        @DisplayName("Forwards delete request to trigger client")
        void forwardsDelete() {
            service.deleteAllSubscriptions(WORKFLOW_ID);
            verify(triggerClient).deleteDatasourceSubscriptionsByWorkflow(WORKFLOW_ID);
        }

        @Test
        @DisplayName("No-op when triggerClient is null (degraded mode)")
        void nullClientIsNoop() {
            DatasourceSubscriptionSyncService degraded =
                    new DatasourceSubscriptionSyncService(null, versionService);

            degraded.deleteAllSubscriptions(WORKFLOW_ID);

            verifyNoInteractions(triggerClient);
        }
    }

    // ──────────────────── default event_types fallback ────────────────────

    @Nested
    @DisplayName("event_types extraction fallbacks")
    class EventTypesFallback {

        @Test
        @DisplayName("Missing event_types defaults to all three event types")
        void missingEventTypesDefaultsToAll() {
            WorkflowEntity wf = workflow(1);
            Map<String, Object> plan = planMapWithDatasourceTrigger(
                    "All Events", 99L, null, null);
            when(versionService.getVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(versionEntity(1, plan)));

            service.syncFromPinnedVersion(wf);

            ArgumentCaptor<DatasourceSubscriptionRequest> captor =
                    ArgumentCaptor.forClass(DatasourceSubscriptionRequest.class);
            verify(triggerClient).createOrUpdateDatasourceSubscription(captor.capture());
            assertThat(captor.getValue().getEventTypes())
                    .containsExactlyInAnyOrder("row_created", "row_updated", "row_deleted");
        }
    }
}
