package com.apimarketplace.datasource.events;

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
        Instant triggeredAt
) {
    public static DatasourceRowEvent created(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row) {
        return new DatasourceRowEvent(EventType.ROW_CREATED, dataSourceId, rowId, tenantId,
                organizationId, row, null, Instant.now());
    }

    public static DatasourceRowEvent updated(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> row, Map<String, Object> previousRow) {
        return new DatasourceRowEvent(EventType.ROW_UPDATED, dataSourceId, rowId, tenantId,
                organizationId, row, previousRow, Instant.now());
    }

    public static DatasourceRowEvent deleted(Long dataSourceId, Long rowId, String tenantId,
                                             String organizationId,
                                             Map<String, Object> lastKnownRow) {
        return new DatasourceRowEvent(EventType.ROW_DELETED, dataSourceId, rowId, tenantId,
                organizationId, lastKnownRow, null, Instant.now());
    }
}
