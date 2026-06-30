package com.apimarketplace.trigger.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DatasourceTriggerSubscriptionDto {
    private Long id;
    private UUID workflowId;
    private Integer planVersion;
    private String triggerId;
    private Long dataSourceId;
    private String tenantId;
    private List<String> eventTypes;
    private Map<String, Object> filter;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public DatasourceTriggerSubscriptionDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
