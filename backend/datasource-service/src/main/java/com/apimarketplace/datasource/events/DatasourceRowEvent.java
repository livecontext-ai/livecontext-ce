package com.apimarketplace.datasource.events;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest.EventType;

import java.time.Instant;
import java.util.Map;

/**
 * Domain event fired inside datasource-service when a row is created, updated,
 * or deleted. Published via ApplicationEventPublisher; consumed by
 * {@link DatasourceRowEventListener} after the DB transaction commits.
 *
 * @param eventType    ROW_CREATED / ROW_UPDATED / ROW_DELETED
 * @param dataSourceId datasource the row belongs to
 * @param rowId        id in data_source_items
 * @param tenantId     tenant scope
 * @param row          row state to expose as {@code trigger.row}
 *                     (current state for create/update, last-known for delete)
 * @param previousRow  pre-change state; non-null only for ROW_UPDATED
 * @param triggeredAt  instant captured after commit
 * @param dataSensitivity {@code RESTRICTED} when the row(s) behind this event carry that stored
 *                     tag (LC-066 re-audit item 1) - carried through to
 *                     {@code DatasourceTriggerDispatchService} so the run a datasource trigger
 *                     starts/advances from a RESTRICTED row is itself marked restricted, the same
 *                     way {@code SubWorkflowNode} taints a child run from its parent's.
 */
public record DatasourceRowEvent(
        EventType eventType,
        Long dataSourceId,
        Long rowId,
        String tenantId,
        /**
         * Workspace org of the datasource. NULL for personal scope. Carried
         * through the @Async @TransactionalEventListener boundary that loses
         * RequestContextHolder; downstream
         * {@code DatasourceTriggerDispatchService} refuses cross-workspace
         * fan-out when this disagrees with the matched workflow's own org.
         */
        String organizationId,
        Map<String, Object> row,
        Map<String, Object> previousRow,
        Instant triggeredAt,
        String dataSensitivity
) {
    public static DatasourceRowEvent created(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row) {
        return created(dataSourceId, rowId, tenantId, organizationId, row, DataSensitivity.NORMAL.name());
    }

    public static DatasourceRowEvent created(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row, String dataSensitivity) {
        return new DatasourceRowEvent(EventType.ROW_CREATED, dataSourceId, rowId, tenantId,
                organizationId, row, null, Instant.now(), DataSensitivity.parse(dataSensitivity).name());
    }

    public static DatasourceRowEvent updated(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row, Map<String, Object> previousRow) {
        return updated(dataSourceId, rowId, tenantId, organizationId, row, previousRow,
                DataSensitivity.NORMAL.name());
    }

    public static DatasourceRowEvent updated(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row, Map<String, Object> previousRow,
                                             String dataSensitivity) {
        return new DatasourceRowEvent(EventType.ROW_UPDATED, dataSourceId, rowId, tenantId,
                organizationId, row, previousRow, Instant.now(), DataSensitivity.parse(dataSensitivity).name());
    }

    public static DatasourceRowEvent deleted(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> lastKnownRow) {
        return deleted(dataSourceId, rowId, tenantId, organizationId, lastKnownRow, DataSensitivity.NORMAL.name());
    }

    public static DatasourceRowEvent deleted(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> lastKnownRow, String dataSensitivity) {
        return new DatasourceRowEvent(EventType.ROW_DELETED, dataSourceId, rowId, tenantId,
                organizationId, lastKnownRow, null, Instant.now(), DataSensitivity.parse(dataSensitivity).name());
    }
}
