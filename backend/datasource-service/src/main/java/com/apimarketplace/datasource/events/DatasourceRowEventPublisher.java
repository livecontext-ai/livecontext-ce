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

    public void publishUpdated(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId,
                               Map<String, Object> row, Map<String, Object> previousRow) {
        publisher.publishEvent(DatasourceRowEvent.updated(
                dataSourceId, rowId, tenantId, organizationId, row, previousRow));
    }

    public void publishDeleted(Long dataSourceId, Long rowId, String tenantId,
                               String organizationId, Map<String, Object> lastKnownRow) {
        publisher.publishEvent(DatasourceRowEvent.deleted(
                dataSourceId, rowId, tenantId, organizationId, lastKnownRow));
    }
}
