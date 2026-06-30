package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.DatasourceSubscriptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pin-aware sync for datasource trigger subscriptions.
 *
 * <p>Mirrors {@link ScheduleSyncService}: only the pinned (production) version's
 * datasource triggers are registered in trigger-service's subscription registry.
 * Draft/unpinned version saves never touch production subscriptions.
 *
 * <p>A subscription row in {@code trigger.datasource_trigger_subscriptions} tells
 * trigger-service which (workflowId, triggerId) to fan a row event out to when
 * datasource-service emits it. Without it, row events have nowhere to go.
 */
@Service
public class DatasourceSubscriptionSyncService {

    private static final Logger logger = LoggerFactory.getLogger(DatasourceSubscriptionSyncService.class);

    private static final String TRIGGER_TYPE_DATASOURCE = "datasource";

    private final TriggerClient triggerClient;
    private final WorkflowPlanVersionService versionService;

    @Autowired
    public DatasourceSubscriptionSyncService(
            @Autowired(required = false) TriggerClient triggerClient,
            @Autowired(required = false) WorkflowPlanVersionService versionService) {
        this.triggerClient = triggerClient;
        this.versionService = versionService;
    }

    /**
     * Syncs datasource subscriptions from the workflow's pinned production version.
     * If no version is pinned, all subscriptions for the workflow are removed.
     */
    public void syncFromPinnedVersion(WorkflowEntity workflow) {
        if (triggerClient == null) {
            logger.warn("[DatasourceSubSync] TriggerClient not available, skipping");
            return;
        }

        Integer pinnedVersion = workflow.getPinnedVersion();
        if (pinnedVersion == null) {
            deleteAllSubscriptions(workflow.getId());
            return;
        }

        WorkflowPlan pinnedPlan = loadPinnedPlan(workflow.getId(), pinnedVersion);
        if (pinnedPlan == null) {
            logger.warn("[DatasourceSubSync] Could not load pinned plan v{} for workflow {}, removing subscriptions",
                    pinnedVersion, workflow.getId());
            deleteAllSubscriptions(workflow.getId());
            return;
        }

        syncFromPlan(workflow, pinnedPlan, pinnedVersion);
    }

    /**
     * Syncs subscriptions from a specific plan. Upserts one subscription per
     * datasource trigger and prunes orphans removed from the plan.
     */
    void syncFromPlan(WorkflowEntity workflow, WorkflowPlan plan, Integer planVersion) {
        List<String> currentTriggerIds = new ArrayList<>();

        if (plan != null && plan.getTriggers() != null) {
            for (Trigger trigger : plan.getTriggers()) {
                if (!TRIGGER_TYPE_DATASOURCE.equalsIgnoreCase(trigger.type())) {
                    continue;
                }
                String triggerId = trigger.getNormalizedKey();
                Long dataSourceId = extractDataSourceId(trigger);
                if (dataSourceId == null) {
                    logger.warn("[DatasourceSubSync] Trigger {} on workflow {} has no datasource_id, skipping",
                            triggerId, workflow.getId());
                    continue;
                }
                List<String> eventTypes = extractEventTypes(trigger.params());
                Map<String, Object> filter = extractFilter(trigger.params());

                try {
                    triggerClient.createOrUpdateDatasourceSubscription(new DatasourceSubscriptionRequest(
                            workflow.getId(), planVersion, triggerId,
                            dataSourceId, workflow.getTenantId(),
                            eventTypes, filter));
                    currentTriggerIds.add(triggerId);
                    logger.info("[DatasourceSubSync] Synced subscription for workflow={} trigger={} ds={} events={}",
                            workflow.getId(), triggerId, dataSourceId, eventTypes);
                } catch (Exception e) {
                    logger.warn("[DatasourceSubSync] Failed to upsert subscription for workflow={} trigger={}: {}",
                            workflow.getId(), triggerId, e.getMessage());
                }
            }
        }

        // Prune orphans - subscriptions for trigger IDs no longer in the plan.
        try {
            triggerClient.pruneDatasourceSubscriptions(workflow.getId(), currentTriggerIds);
        } catch (Exception e) {
            logger.warn("[DatasourceSubSync] Failed to prune subscriptions for workflow {}: {}",
                    workflow.getId(), e.getMessage());
        }
    }

    /**
     * Deletes all subscriptions for a workflow. Used when unpinned or when the
     * pinned plan is unresolvable.
     */
    public void deleteAllSubscriptions(UUID workflowId) {
        if (triggerClient == null) return;
        try {
            triggerClient.deleteDatasourceSubscriptionsByWorkflow(workflowId);
            logger.info("[DatasourceSubSync] Removed all datasource subscriptions for workflow {}", workflowId);
        } catch (Exception e) {
            logger.warn("[DatasourceSubSync] Failed to remove subscriptions for workflow {}: {}",
                    workflowId, e.getMessage());
        }
    }

    WorkflowPlan loadPinnedPlan(UUID workflowId, int pinnedVersion) {
        if (versionService == null) {
            logger.warn("[DatasourceSubSync] WorkflowPlanVersionService not available");
            return null;
        }
        try {
            Optional<WorkflowPlanVersionEntity> versionOpt = versionService.getVersion(workflowId, pinnedVersion);
            if (versionOpt.isEmpty()) {
                logger.warn("[DatasourceSubSync] Pinned version {} not found for workflow {}",
                        pinnedVersion, workflowId);
                return null;
            }
            Map<String, Object> planMap = versionOpt.get().getPlan();
            if (planMap == null) return null;
            return WorkflowPlan.fromMap(planMap, null);
        } catch (Exception e) {
            logger.error("[DatasourceSubSync] Failed to load pinned plan v{} for workflow {}: {}",
                    pinnedVersion, workflowId, e.getMessage());
            return null;
        }
    }

    private Long extractDataSourceId(Trigger trigger) {
        // TriggerCreator stores datasource_id at the trigger root and also mirrors
        // it in params; accept either shape to be resilient to plan format drift.
        Object candidate = null;
        Map<String, Object> params = trigger.params();
        if (params != null) {
            candidate = params.get("datasource_id");
            if (candidate == null) candidate = params.get("dataSourceId");
            if (candidate == null) candidate = params.get("table_id");
        }
        if (candidate == null && trigger.id() != null && !trigger.id().isBlank()) {
            // The trigger id itself holds the datasource id when builder uses the
            // datasource id as the trigger id (see TriggerCreator.buildTriggerNode).
            candidate = trigger.id();
        }
        if (candidate == null) return null;
        try {
            if (candidate instanceof Number n) return n.longValue();
            return Long.parseLong(candidate.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractEventTypes(Map<String, Object> params) {
        if (params == null) return List.of("row_created", "row_updated", "row_deleted");
        Object raw = params.get("event_types");
        if (raw == null) raw = params.get("eventTypes");
        if (raw instanceof Collection<?> col) {
            List<String> out = new ArrayList<>();
            for (Object o : col) {
                if (o != null) out.add(o.toString().toLowerCase());
            }
            if (!out.isEmpty()) return out;
        }
        return List.of("row_created", "row_updated", "row_deleted");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFilter(Map<String, Object> params) {
        if (params == null) return null;
        Object raw = params.get("filter");
        if (raw instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return null;
    }
}
