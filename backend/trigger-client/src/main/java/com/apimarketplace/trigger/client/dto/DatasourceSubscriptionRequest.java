package com.apimarketplace.trigger.client.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Upsert payload for a datasource trigger subscription, sent from orchestrator
 * (DatasourceSubscriptionSyncService) to trigger-service when a workflow is
 * pinned/saved.
 */
public class DatasourceSubscriptionRequest {
    private UUID workflowId;
    private Integer planVersion;
    private String triggerId;
    private Long dataSourceId;
    private String tenantId;
    // OWNER org of the workflow this subscription belongs to. Passed explicitly so
    // the org-scoped subscription row is stamped from the workflow owner, never from
    // the orchestrator request's ambient org (which can diverge - cross-tenant bleed).
    // Mirrors the sibling schedule-sync path.
    private String organizationId;
    private List<String> eventTypes;
    private Map<String, Object> filter;

    public DatasourceSubscriptionRequest() {}

    // Back-compat 7-arg (no org) - delegates with a null org. Production sync now
    // uses the 8-arg variant; the null path leaves org to the listener (legacy).
    public DatasourceSubscriptionRequest(UUID workflowId, Integer planVersion, String triggerId,
                                         Long dataSourceId, String tenantId,
                                         List<String> eventTypes, Map<String, Object> filter) {
        this(workflowId, planVersion, triggerId, dataSourceId, tenantId, null, eventTypes, filter);
    }

    public DatasourceSubscriptionRequest(UUID workflowId, Integer planVersion, String triggerId,
                                         Long dataSourceId, String tenantId, String organizationId,
                                         List<String> eventTypes, Map<String, Object> filter) {
        this.workflowId = workflowId;
        this.planVersion = planVersion;
        this.triggerId = triggerId;
        this.dataSourceId = dataSourceId;
        this.tenantId = tenantId;
        this.organizationId = organizationId;
        this.eventTypes = eventTypes;
        this.filter = filter;
    }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public List<String> getEventTypes() { return eventTypes; }
    public void setEventTypes(List<String> eventTypes) { this.eventTypes = eventTypes; }
    public Map<String, Object> getFilter() { return filter; }
    public void setFilter(Map<String, Object> filter) { this.filter = filter; }
}
