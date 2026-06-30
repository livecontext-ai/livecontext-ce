package com.apimarketplace.trigger.client.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Payload sent by datasource-service to trigger-service when a row is
 * created/updated/deleted. trigger-service fans out to matching subscriptions.
 */
public class DatasourceEventDispatchRequest {

    public enum EventType { ROW_CREATED, ROW_UPDATED, ROW_DELETED }

    private EventType eventType;
    private Long dataSourceId;
    private Long rowId;
    private String tenantId;
    /**
     * Workspace org of the datasource that produced the row event. NULL for
     * personal scope. Added 2026-05-18 to carry the org context through the
     * async @TransactionalEventListener boundary that loses RequestContextHolder
     * - downstream dispatchers compare this against the matched workflow's
     * own organization_id and refuse cross-workspace fan-out.
     */
    private String organizationId;
    private Map<String, Object> row;
    private Map<String, Object> previousRow;
    private Instant triggeredAt;

    public DatasourceEventDispatchRequest() {}

    public DatasourceEventDispatchRequest(EventType eventType, Long dataSourceId, Long rowId,
                                          String tenantId, String organizationId,
                                          Map<String, Object> row,
                                          Map<String, Object> previousRow, Instant triggeredAt) {
        this.eventType = eventType;
        this.dataSourceId = dataSourceId;
        this.rowId = rowId;
        this.tenantId = tenantId;
        this.organizationId = organizationId;
        this.row = row;
        this.previousRow = previousRow;
        this.triggeredAt = triggeredAt;
    }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public Long getRowId() { return rowId; }
    public void setRowId(Long rowId) { this.rowId = rowId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public Map<String, Object> getRow() { return row; }
    public void setRow(Map<String, Object> row) { this.row = row; }
    public Map<String, Object> getPreviousRow() { return previousRow; }
    public void setPreviousRow(Map<String, Object> previousRow) { this.previousRow = previousRow; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }
}
