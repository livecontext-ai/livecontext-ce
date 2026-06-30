package com.apimarketplace.datasource.events;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * Builds the per-datasource HNSW index when a vector column is created.
 * Without it, every similarity search sequential-scans all of the
 * datasource's vectors (the index-creation code existed since V75 but was
 * never wired to any caller - searches were O(N) distance computations).
 *
 * <p>AFTER_COMMIT (the column must be durable before the index references its
 * config) + @Async (CREATE INDEX CONCURRENTLY can wait on other transactions;
 * never park an HTTP thread on it). Failures are logged and swallowed:
 * similarity search works without the index, just slower.
 *
 * <p>Known limit, accepted: the partial index covers ALL of the datasource's
 * vectors with one dimension cast. A second vector column with a DIFFERENT
 * dimension on the same datasource makes the build fail (cast error on the
 * other column's rows) - logged, search falls back to seq scan. One dimension
 * per datasource is the supported layout.
 */
@Component
public class VectorColumnCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(VectorColumnCreatedListener.class);

    private final VectorRepository vectorRepository;

    public VectorColumnCreatedListener(VectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    // fallbackExecution: the add_columns path publishes from inside the
    // @Transactional CRUD executor (AFTER_COMMIT applies), but table creation
    // (DataSourceService.createDataSource) runs WITHOUT a transaction - the
    // default fallbackExecution=false silently DROPS the event there (caught
    // live by the CE e2e: table created, index never built). Out of a
    // transaction the column row is already committed (autocommit), so
    // immediate execution is safe.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVectorColumnCreated(VectorColumnCreatedEvent event) {
        try {
            vectorRepository.createHnswIndex(event.dataSourceId(), event.dimension(), event.metric());
        } catch (Exception e) {
            log.warn("[VectorColumnCreatedListener] HNSW index build skipped for datasource {} (dim={}): {} - similarity search will seq-scan",
                    event.dataSourceId(), event.dimension(), e.getMessage());
        }
    }
}
