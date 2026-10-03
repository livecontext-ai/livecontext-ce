package com.apimarketplace.datasource.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Thin facade over {@link ApplicationEventPublisher} so service layers don't
 * depend directly on Spring's event API. Kept intentionally minimal - the
 * heavy lifting (fetching before/after row snapshots) is each caller's
 * responsibility, since only they know the shape of the operation.
 */
@Component
public class DatasourceRowEventPublisher {

    private final ApplicationEventPublisher publisher;

    public DatasourceRowEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishCreated(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId, Map<String, Object> row) {
        publisher.publishEvent(DatasourceRowEvent.created(
                dataSourceId, rowId, tenantId, organizationId, row));
    }

    /**
     * LC-066 re-audit item 1 overload: {@code dataSensitivity} is the row's own stored
     * classification (see {@code CrudExecutorService.RowSnapshots}), carried through so a
     * table trigger fired from a RESTRICTED row marks the run it starts/advances restricted.
     */
    public void publishCreated(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId, Map<String, Object> row, String dataSensitivity) {
        publisher.publishEvent(DatasourceRowEvent.created(
                dataSourceId, rowId, tenantId, organizationId, row, dataSensitivity));
    }

    public void publishUpdated(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId,
                               Map<String, Object> row, Map<String, Object> previousRow) {
        publisher.publishEvent(DatasourceRowEvent.updated(
                dataSourceId, rowId, tenantId, organizationId, row, previousRow));
    }

    /** See {@link #publishCreated(Long, Long, String, String, Map, String)}. */
    public void publishUpdated(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId,
                               Map<String, Object> row, Map<String, Object> previousRow,
                               String dataSensitivity) {
        publisher.publishEvent(DatasourceRowEvent.updated(
                dataSourceId, rowId, tenantId, organizationId, row, previousRow, dataSensitivity));
    }

    public void publishDeleted(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId, Map<String, Object> lastKnownRow) {
        publisher.publishEvent(DatasourceRowEvent.deleted(
                dataSourceId, rowId, tenantId, organizationId, lastKnownRow));
    }

    /** See {@link #publishCreated(Long, Long, String, String, Map, String)}. */
    public void publishDeleted(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId, Map<String, Object> lastKnownRow, String dataSensitivity) {
        publisher.publishEvent(DatasourceRowEvent.deleted(
                dataSourceId, rowId, tenantId, organizationId, lastKnownRow, dataSensitivity));
    }
}
