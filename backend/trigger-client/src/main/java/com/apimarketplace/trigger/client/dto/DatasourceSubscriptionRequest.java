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
    private List<String> eventTypes;
    private Map<String, Object> filter;

    public DatasourceSubscriptionRequest() {}

    public DatasourceSubscriptionRequest(UUID workflowId, Integer planVersion, String triggerId,
                                         Long dataSourceId, String tenantId,
                                         List<String> eventTypes, Map<String, Object> filter) {
        this.workflowId = workflowId;
        this.planVersion = planVersion;
        this.triggerId = triggerId;
        this.dataSourceId = dataSourceId;
        this.tenantId = tenantId;
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
    public List<String> getEventTypes() { return eventTypes; }
    public void setEventTypes(List<String> eventTypes) { this.eventTypes = eventTypes; }
    public Map<String, Object> getFilter() { return filter; }
    public void setFilter(Map<String, Object> filter) { this.filter = filter; }
}
