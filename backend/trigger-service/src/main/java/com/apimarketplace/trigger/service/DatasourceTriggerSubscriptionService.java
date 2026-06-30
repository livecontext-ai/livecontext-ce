package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest;
import com.apimarketplace.trigger.client.dto.DatasourceSubscriptionRequest;
import com.apimarketplace.trigger.client.dto.DatasourceTriggerSubscriptionDto;
import com.apimarketplace.trigger.domain.DatasourceTriggerSubscriptionEntity;
import com.apimarketplace.trigger.repository.DatasourceTriggerSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the datasource trigger subscription registry.
 *
 * Two roles:
 *  - CRUD: orchestrator's sync service upserts/prunes/deletes subscriptions
 *    when workflows are pinned or deleted.
 *  - Dispatch: on a row event from datasource-service, fan out to every matching
 *    subscription (eventType ∈ subscription.eventTypes + filter matches) and
 *    call orchestrator's fire endpoint once per match.
 *
 * Each fire is best-effort - a failure on one subscription does not affect others.
 */
@Service
public class DatasourceTriggerSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceTriggerSubscriptionService.class);

    private final DatasourceTriggerSubscriptionRepository repository;
    private final DatasourceFilterEvaluator filterEvaluator;
    private final OrchestratorFireClient orchestratorClient;

    public DatasourceTriggerSubscriptionService(DatasourceTriggerSubscriptionRepository repository,
                                                DatasourceFilterEvaluator filterEvaluator,
                                                OrchestratorFireClient orchestratorClient) {
        this.repository = repository;
        this.filterEvaluator = filterEvaluator;
        this.orchestratorClient = orchestratorClient;
    }

    @Transactional
    public DatasourceTriggerSubscriptionDto upsert(DatasourceSubscriptionRequest request) {
        Optional<DatasourceTriggerSubscriptionEntity> existing =
                repository.findByWorkflowIdAndTriggerId(request.getWorkflowId(), request.getTriggerId());

        DatasourceTriggerSubscriptionEntity entity = existing.orElseGet(DatasourceTriggerSubscriptionEntity::new);
        entity.setWorkflowId(request.getWorkflowId());
        entity.setPlanVersion(request.getPlanVersion());
        entity.setTriggerId(request.getTriggerId());
        entity.setDataSourceId(request.getDataSourceId());
        entity.setTenantId(request.getTenantId());
        // Stamp the org explicitly from the workflow owner (carried on the request)
        // so the org-scoped subscription row is never left to the orchestrator
        // request's ambient org, which can diverge (cross-tenant bleed). When the
        // request carries no org (legacy 7-arg path), leave it to the listener.
        if (request.getOrganizationId() != null && !request.getOrganizationId().isBlank()) {
            entity.setOrganizationId(request.getOrganizationId());
        }
        entity.setEventTypes(request.getEventTypes());
        entity.setFilter(request.getFilter());
        entity.setActive(true);
        entity.setUpdatedAt(Instant.now());

        DatasourceTriggerSubscriptionEntity saved = repository.save(entity);
        return toDto(saved);
    }

    public List<DatasourceTriggerSubscriptionDto> findByWorkflow(UUID workflowId) {
        return repository.findByWorkflowId(workflowId).stream().map(this::toDto).toList();
    }

    @Transactional
    public void deleteByWorkflow(UUID workflowId) {
        repository.deleteByWorkflowId(workflowId);
    }

    @Transactional
    public int prune(UUID workflowId, List<String> currentTriggerIds) {
        if (currentTriggerIds == null || currentTriggerIds.isEmpty()) {
            // If current list is empty, delete ALL subscriptions for this workflow.
            List<DatasourceTriggerSubscriptionEntity> all = repository.findByWorkflowId(workflowId);
            repository.deleteAll(all);
            return all.size();
        }
        return repository.deleteByWorkflowIdAndTriggerIdNotIn(workflowId, currentTriggerIds);
    }

    /**
     * Fan out a row event to every matching subscription. Called by
     * InternalDatasourceTriggerController.dispatchEvent.
     *
     * @return number of workflows actually fired (filter matches only)
     */
    public int dispatchEvent(DatasourceEventDispatchRequest event) {
        if (event == null || event.getDataSourceId() == null || event.getEventType() == null) {
            log.warn("Ignoring malformed datasource event: {}", event);
            return 0;
        }
        List<DatasourceTriggerSubscriptionEntity> candidates =
                repository.findByDataSourceIdAndActiveTrue(event.getDataSourceId());

        String eventTypeKey = event.getEventType().name().toLowerCase(); // ROW_CREATED → "row_created"
        int fired = 0;

        for (DatasourceTriggerSubscriptionEntity sub : candidates) {
            List<String> subscribedEvents = sub.getEventTypes();
            if (subscribedEvents == null || !subscribedEvents.contains(eventTypeKey)) {
                continue;
            }
            // Apply filter against the row for creates/updates, against previous row for deletes
            // where the row is the last-known snapshot (same semantics).
            if (!filterEvaluator.matches(sub.getFilter(), event.getRow())) {
                continue;
            }
            orchestratorClient.fire(sub.getWorkflowId(), sub.getTriggerId(), sub.getTenantId(), event);
            fired++;
        }

        if (fired == 0) {
            log.debug("No subscriptions matched datasource={} event={}",
                    event.getDataSourceId(), eventTypeKey);
        }
        return fired;
    }

    private DatasourceTriggerSubscriptionDto toDto(DatasourceTriggerSubscriptionEntity e) {
        DatasourceTriggerSubscriptionDto dto = new DatasourceTriggerSubscriptionDto();
        dto.setId(e.getId());
        dto.setWorkflowId(e.getWorkflowId());
        dto.setPlanVersion(e.getPlanVersion());
        dto.setTriggerId(e.getTriggerId());
        dto.setDataSourceId(e.getDataSourceId());
        dto.setTenantId(e.getTenantId());
        dto.setEventTypes(e.getEventTypes());
        dto.setFilter(e.getFilter());
        dto.setActive(e.isActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
