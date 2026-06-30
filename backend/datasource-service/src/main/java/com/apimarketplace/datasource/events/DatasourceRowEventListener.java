package com.apimarketplace.datasource.events;

import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestTemplate;

/**
 * Listens for {@link DatasourceRowEvent}s published by the CRUD/enhanced
 * services and dispatches them to trigger-service AFTER the DB transaction
 * commits. Async so the write path is never blocked by network I/O.
 *
 * Fire-and-forget: a failure to reach trigger-service logs a warning but does
 * not affect the row write (at-most-once delivery - acceptable trade-off for
 * MVP; durable outbox can be added later without changing the publisher API).
 */
@Component
public class DatasourceRowEventListener {

    private static final Logger log = LoggerFactory.getLogger(DatasourceRowEventListener.class);

    private final TriggerClient triggerClient;

    public DatasourceRowEventListener(
            @Value("${services.trigger-url:http://localhost:8091}") String triggerUrl) {
        this.triggerClient = new TriggerClient(new RestTemplate(), triggerUrl);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRowEvent(DatasourceRowEvent event) {
        DatasourceEventDispatchRequest request = new DatasourceEventDispatchRequest(
                event.eventType(),
                event.dataSourceId(),
                event.rowId(),
                event.tenantId(),
                event.organizationId(),
                event.row(),
                event.previousRow(),
                event.triggeredAt()
        );
        log.debug("Dispatching {} for datasource={} row={} org={}",
                event.eventType(), event.dataSourceId(), event.rowId(), event.organizationId());
        triggerClient.dispatchDatasourceEvent(request);
    }
}
