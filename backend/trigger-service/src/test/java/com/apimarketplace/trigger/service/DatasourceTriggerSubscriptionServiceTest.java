package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest;
import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest.EventType;
import com.apimarketplace.trigger.client.dto.DatasourceSubscriptionRequest;
import com.apimarketplace.trigger.domain.DatasourceTriggerSubscriptionEntity;
import com.apimarketplace.trigger.repository.DatasourceTriggerSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DatasourceTriggerSubscriptionService")
class DatasourceTriggerSubscriptionServiceTest {

    private DatasourceTriggerSubscriptionRepository repository;
    private DatasourceFilterEvaluator filterEvaluator;
    private OrchestratorFireClient orchestratorClient;
    private DatasourceTriggerSubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(DatasourceTriggerSubscriptionRepository.class);
        filterEvaluator = new DatasourceFilterEvaluator(); // real evaluator - integration-style
        orchestratorClient = mock(OrchestratorFireClient.class);
        service = new DatasourceTriggerSubscriptionService(repository, filterEvaluator, orchestratorClient);
    }

    // ==================== Upsert ====================

    @Test
    @DisplayName("upsert creates a new row when no subscription exists for (workflow, trigger)")
    void upsertCreatesWhenMissing() {
        UUID workflowId = UUID.randomUUID();
        DatasourceSubscriptionRequest request = new DatasourceSubscriptionRequest(
                workflowId, 1, "trigger:on_new_user", 42L, "tenant-A",
                List.of("row_created"), null);
        when(repository.findByWorkflowIdAndTriggerId(workflowId, "trigger:on_new_user"))
                .thenReturn(Optional.empty());
        when(repository.save(any(DatasourceTriggerSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    DatasourceTriggerSubscriptionEntity e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

        var result = service.upsert(request);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDataSourceId()).isEqualTo(42L);
        assertThat(result.getEventTypes()).containsExactly("row_created");
        assertThat(result.isActive()).isTrue();
    }

    @Test
    @DisplayName("upsert updates the existing row when subscription already exists (idempotent sync)")
    void upsertUpdatesExisting() {
        UUID workflowId = UUID.randomUUID();
        DatasourceTriggerSubscriptionEntity existing = new DatasourceTriggerSubscriptionEntity();
        existing.setId(99L);
        existing.setWorkflowId(workflowId);
        existing.setTriggerId("trigger:x");
        existing.setEventTypes(List.of("row_created")); // old value
        existing.setActive(false); // previously deactivated

        when(repository.findByWorkflowIdAndTriggerId(workflowId, "trigger:x"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(DatasourceTriggerSubscriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasourceSubscriptionRequest request = new DatasourceSubscriptionRequest(
                workflowId, 2, "trigger:x", 10L, "tenant-A",
                List.of("row_created", "row_updated"), Map.of("column", "s", "operator", "eq", "value", "ok"));

        var result = service.upsert(request);

        ArgumentCaptor<DatasourceTriggerSubscriptionEntity> captor =
                ArgumentCaptor.forClass(DatasourceTriggerSubscriptionEntity.class);
        verify(repository).save(captor.capture());
        DatasourceTriggerSubscriptionEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(99L); // reused PK - no duplicate rows
        assertThat(saved.getEventTypes()).containsExactly("row_created", "row_updated");
        assertThat(saved.getFilter()).containsEntry("operator", "eq");
        assertThat(saved.isActive()).isTrue(); // always re-activated on upsert
        assertThat(result.isActive()).isTrue();
    }

    // ==================== Prune ====================

    @Test
    @DisplayName("prune with empty currentTriggerIds deletes ALL subscriptions for the workflow")
    void pruneEmptyListDeletesAll() {
        UUID workflowId = UUID.randomUUID();
        DatasourceTriggerSubscriptionEntity a = new DatasourceTriggerSubscriptionEntity();
        a.setId(1L);
        DatasourceTriggerSubscriptionEntity b = new DatasourceTriggerSubscriptionEntity();
        b.setId(2L);
        when(repository.findByWorkflowId(workflowId)).thenReturn(List.of(a, b));

        int removed = service.prune(workflowId, List.of());

        assertThat(removed).isEqualTo(2);
        verify(repository).deleteAll(List.of(a, b));
        verify(repository, never()).deleteByWorkflowIdAndTriggerIdNotIn(any(), any());
    }

    @Test
    @DisplayName("prune with a non-empty list delegates to repository.deleteByWorkflowIdAndTriggerIdNotIn")
    void pruneNonEmptyDelegatesToRepo() {
        UUID workflowId = UUID.randomUUID();
        when(repository.deleteByWorkflowIdAndTriggerIdNotIn(workflowId, List.of("trigger:a"))).thenReturn(3);

        int removed = service.prune(workflowId, List.of("trigger:a"));

        assertThat(removed).isEqualTo(3);
        verify(repository, never()).deleteAll(any(Iterable.class));
    }

    // ==================== Dispatch ====================

    @Test
    @DisplayName("dispatchEvent fires one call per subscription matching eventType + filter")
    void dispatchFiresMatchingSubscriptions() {
        long dsId = 10L;
        DatasourceTriggerSubscriptionEntity matching = sub(UUID.randomUUID(), "trigger:a", dsId,
                List.of("row_created"), null);
        DatasourceTriggerSubscriptionEntity wrongEventType = sub(UUID.randomUUID(), "trigger:b", dsId,
                List.of("row_deleted"), null);
        DatasourceTriggerSubscriptionEntity filterRejects = sub(UUID.randomUUID(), "trigger:c", dsId,
                List.of("row_created"),
                Map.of("column", "status", "operator", "eq", "value", "archived"));

        when(repository.findByDataSourceIdAndActiveTrue(dsId))
                .thenReturn(List.of(matching, wrongEventType, filterRejects));

        DatasourceEventDispatchRequest event = new DatasourceEventDispatchRequest(
                EventType.ROW_CREATED, dsId, 555L, "tenant-A", null,
                Map.of("status", "new"), null, Instant.now());

        int fired = service.dispatchEvent(event);

        assertThat(fired).isEqualTo(1);
        verify(orchestratorClient).fire(eq(matching.getWorkflowId()), eq("trigger:a"),
                eq(matching.getTenantId()), eq(event));
        verify(orchestratorClient, never()).fire(eq(wrongEventType.getWorkflowId()), any(), any(), any());
        verify(orchestratorClient, never()).fire(eq(filterRejects.getWorkflowId()), any(), any(), any());
    }

    @Test
    @DisplayName("dispatchEvent returns 0 and fires nothing when dataSourceId has no subscriptions")
    void dispatchNoSubscriptions() {
        when(repository.findByDataSourceIdAndActiveTrue(anyLong())).thenReturn(List.of());

        DatasourceEventDispatchRequest event = new DatasourceEventDispatchRequest(
                EventType.ROW_CREATED, 42L, 1L, "t", null, Map.of(), null, Instant.now());

        assertThat(service.dispatchEvent(event)).isZero();
        verifyNoInteractions(orchestratorClient);
    }

    @Test
    @DisplayName("dispatchEvent ignores malformed events (missing dataSourceId or eventType)")
    void dispatchIgnoresMalformed() {
        DatasourceEventDispatchRequest missingDs = new DatasourceEventDispatchRequest(
                EventType.ROW_CREATED, null, 1L, "t", null, Map.of(), null, Instant.now());
        DatasourceEventDispatchRequest missingEvent = new DatasourceEventDispatchRequest(
                null, 42L, 1L, "t", null, Map.of(), null, Instant.now());

        assertThat(service.dispatchEvent(missingDs)).isZero();
        assertThat(service.dispatchEvent(missingEvent)).isZero();
        assertThat(service.dispatchEvent(null)).isZero();
        verifyNoInteractions(repository);
        verifyNoInteractions(orchestratorClient);
    }

    private DatasourceTriggerSubscriptionEntity sub(UUID wfId, String triggerId, long dsId,
                                                    List<String> eventTypes, Map<String, Object> filter) {
        DatasourceTriggerSubscriptionEntity e = new DatasourceTriggerSubscriptionEntity();
        e.setWorkflowId(wfId);
        e.setTriggerId(triggerId);
        e.setDataSourceId(dsId);
        e.setTenantId("tenant-A");
        e.setEventTypes(eventTypes);
        e.setFilter(filter);
        e.setActive(true);
        return e;
    }
}
