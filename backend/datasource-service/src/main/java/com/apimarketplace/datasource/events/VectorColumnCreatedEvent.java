package com.apimarketplace.datasource.events;

/**
 * Published when a VECTOR column is added to a datasource (table create or
 * add_columns), AFTER the column lands in the mappingSpec. Consumed post-commit
 * by {@link VectorColumnCreatedListener} to build the per-datasource HNSW
 * index.
 *
 * <p><b>Why an event instead of an inline call:</b> {@code CREATE INDEX
 * CONCURRENTLY} cannot run while the creating transaction is still open - it
 * waits for every transaction with an older snapshot, including the caller's
 * own CRUD transaction, which would self-deadlock until timeout. The
 * AFTER_COMMIT + @Async listener (same pattern as {@link DatasourceRowEvent})
 * runs once the transaction is gone.
 *
 * @param dataSourceId the datasource that received the vector column
 * @param dimension    embedding size from the column's {@code display.dimension}
 * @param metric       similarity metric from {@code display.metric} (cosine/l2/dot)
 */
public record VectorColumnCreatedEvent(Long dataSourceId, int dimension, String metric) {
}
