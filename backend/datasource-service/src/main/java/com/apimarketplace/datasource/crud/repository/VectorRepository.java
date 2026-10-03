package com.apimarketplace.datasource.crud.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Repository for vector storage and similarity search operations.
 * Manages the data_source_vectors table separately from the main JSONB data.
 */
@Repository
public class VectorRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorRepository.class);
    private static final String VECTOR_TABLE = "data_source_vectors";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public VectorRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Insert a vector for a specific item and column.
     * Uses ON CONFLICT to upsert (supports re-embedding).
     */
    @Transactional
    public void insertVector(Long dataSourceId, String tenantId, Long itemId,
                             String columnName, float[] embedding) {
        String vectorLiteral = toVectorLiteral(embedding);

        String sql = String.format(
            "INSERT INTO %s (data_source_id, tenant_id, item_id, column_name, embedding, dimension, created_at) " +
            "VALUES (:data_source_id, :tenant_id, :item_id, :column_name, :embedding::vector, :dimension, NOW()) " +
            "ON CONFLICT (item_id, column_name) DO UPDATE SET " +
            "embedding = EXCLUDED.embedding, dimension = EXCLUDED.dimension, created_at = NOW()",
            VECTOR_TABLE
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);
        params.addValue("item_id", itemId);
        params.addValue("column_name", columnName);
        params.addValue("embedding", vectorLiteral);
        params.addValue("dimension", embedding.length);

        jdbcTemplate.update(sql, params);
        log.debug("Inserted vector for item {} column '{}' (dim={})", itemId, columnName, embedding.length);
    }

    /**
     * Batch insert vectors for multiple items using a single multi-row INSERT.
     * Uses ON CONFLICT to upsert (supports re-embedding).
     */
    @Transactional
    public void insertVectorBatch(Long dataSourceId, String tenantId,
                                  List<VectorEntry> entries) {
        if (entries.isEmpty()) return;

        // Batch in groups of 100 to avoid overly large SQL statements
        int batchSize = 100;
        for (int start = 0; start < entries.size(); start += batchSize) {
            int end = Math.min(start + batchSize, entries.size());
            List<VectorEntry> batch = entries.subList(start, end);

            StringBuilder sql = new StringBuilder();
            sql.append(String.format(
                "INSERT INTO %s (data_source_id, tenant_id, item_id, column_name, embedding, dimension, created_at) VALUES ",
                VECTOR_TABLE));

            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("data_source_id", dataSourceId);
            params.addValue("tenant_id", tenantId);

            for (int i = 0; i < batch.size(); i++) {
                VectorEntry entry = batch.get(i);
                String vectorLiteral = toVectorLiteral(entry.embedding());
                String emb = "emb_" + i;
                String dim = "dim_" + i;
                String item = "item_" + i;
                String col = "col_" + i;

                if (i > 0) sql.append(", ");
                sql.append(String.format("(:data_source_id, :tenant_id, :%s, :%s, :%s::vector, :%s, NOW())",
                    item, col, emb, dim));

                params.addValue(item, entry.itemId());
                params.addValue(col, entry.columnName());
                params.addValue(emb, vectorLiteral);
                params.addValue(dim, entry.embedding().length);
            }

            sql.append(" ON CONFLICT (item_id, column_name) DO UPDATE SET ");
            sql.append("embedding = EXCLUDED.embedding, dimension = EXCLUDED.dimension, created_at = NOW()");

            jdbcTemplate.update(sql.toString(), params);
        }
        log.info("Batch inserted {} vectors for datasource {}", entries.size(), dataSourceId);
    }

    /**
     * Perform similarity search using pgvector distance operators.
     * Supports optional WHERE filtering on JSONB data columns (hybrid search).
     *
     * @param dataSourceId the datasource to search in
     * @param tenantId     tenant isolation
     * @param columnName   the vector column to search
     * @param queryVector  the query embedding
     * @param dimension    expected dimension (for casting)
     * @param metric       distance metric: "cosine", "l2", or "dot"
     * @param topK         max results
     * @param threshold    optional distance threshold
     * @param where        optional WHERE condition on JSONB data (can be null)
     * @return rows with distance score, ordered by similarity
     */
    public List<Map<String, Object>> similaritySearch(
            Long dataSourceId, String tenantId, String columnName,
            float[] queryVector, int dimension, String metric,
            int topK, Float threshold,
            String whereClause, MapSqlParameterSource extraParams) {

        String distanceOp = switch (metric != null ? metric : "cosine") {
            case "l2" -> "<->";
            case "dot" -> "<#>";
            default -> "<=>";
        };

        String vectorCast = "::vector(" + dimension + ")";
        String queryLiteral = toVectorLiteral(queryVector);

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tenant_id", tenantId);
        params.addValue("column_name", columnName);
        params.addValue("query_vector", queryLiteral);
        params.addValue("top_k", topK);

        // Merge extra params from WHERE clause
        if (extraParams != null) {
            for (String name : extraParams.getParameterNames()) {
                params.addValue(name, extraParams.getValue(name));
            }
        }

        StringBuilder sql = new StringBuilder();
        // LC-066 re-audit item 2: i.data_sensitivity joined in so a RESTRICTED matched row tags
        // the similarity-search result the same way executeReadRow already ORs in each row's own
        // stored tag (see CrudExecutorService.readResultSensitivity).
        sql.append("SELECT i.id, i.data, i.priority, i.created_at, i.data_sensitivity, ");
        sql.append("(v.embedding").append(vectorCast).append(" ").append(distanceOp);
        sql.append(" :query_vector").append(vectorCast).append(") AS distance ");
        sql.append("FROM ").append(VECTOR_TABLE).append(" v ");
        sql.append("JOIN data_source_items i ON v.item_id = i.id ");
        // data_source_id is inlined as a LITERAL, deliberately, and it is the one
        // predicate that must be. The HNSW index is PARTIAL on
        // "WHERE data_source_id = 42", and the planner can only use it when it can
        // PROVE the query's predicate implies the index's. With a bind parameter
        // that proof is impossible under a generic plan: the index is silently
        // dropped for a Bitmap Heap Scan + Sort that returns the same rows 153x
        // slower (measured: 0.685 ms vs 105 ms). Today production is protected only
        // because every JDBC URL carries prepareThreshold=0 for PgBouncer, which
        // incidentally forces custom plans; this makes the query correct regardless
        // of plan mode. The value is a Long from our own database, never user text.
        sql.append("WHERE v.data_source_id = ").append(dataSourceId.longValue()).append(' ');
        sql.append("AND v.tenant_id = :tenant_id ");
        sql.append("AND v.column_name = :column_name ");

        if (threshold != null) {
            params.addValue("threshold", threshold);
            sql.append("AND (v.embedding").append(vectorCast).append(" ").append(distanceOp);
            sql.append(" :query_vector").append(vectorCast).append(") < :threshold ");
        }

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append("AND ").append(whereClause).append(" ");
        }

        sql.append("ORDER BY distance LIMIT :top_k");

        log.debug("Executing similarity search: {}", sql);
        return jdbcTemplate.queryForList(sql.toString(), params);
    }

    /**
     * Get truncated vector previews for a list of item IDs.
     * Returns a map of itemId -> map of columnName -> truncated string
     * (e.g., "[-0.009573, -0.021607, 0.029964, ... (1536 dims)]").
     * Uses embedding::text to get the pgvector string representation,
     * then truncates in Java to include only the first N elements.
     */
    public Map<Long, Map<String, String>> getVectorPreviewsForItems(List<Long> itemIds, String tenantId) {
        if (itemIds == null || itemIds.isEmpty()) return Map.of();

        String sql = String.format(
            "SELECT item_id, column_name, dimension, embedding::text AS vec_text " +
            "FROM %s WHERE item_id IN (:item_ids) AND tenant_id = :tenant_id",
            VECTOR_TABLE
        );
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("item_ids", itemIds);
        params.addValue("tenant_id", tenantId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);

        Map<Long, Map<String, String>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long itemId = ((Number) row.get("item_id")).longValue();
            String colName = (String) row.get("column_name");
            int dim = ((Number) row.get("dimension")).intValue();
            String vecText = (String) row.get("vec_text");

            result.computeIfAbsent(itemId, k -> new java.util.LinkedHashMap<>()).put(colName, vecText);
        }
        return result;
    }


    /**
     * Delete all vectors for a specific item (with tenant isolation).
     */
    public void deleteByItemId(Long itemId, String tenantId) {
        String sql = String.format("DELETE FROM %s WHERE item_id = :item_id AND tenant_id = :tenant_id", VECTOR_TABLE);
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("item_id", itemId);
        params.addValue("tenant_id", tenantId);
        jdbcTemplate.update(sql, params);
    }

    /**
     * Delete all vectors for a datasource (with tenant isolation).
     */
    public void deleteByDataSourceId(Long dataSourceId, String tenantId) {
        String sql = String.format("DELETE FROM %s WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id", VECTOR_TABLE);
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);
        jdbcTemplate.update(sql, params);
    }

    /**
     * Create a partial HNSW index for a specific datasource.
     * Must be called outside a transaction (CONCURRENTLY).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createHnswIndex(Long dataSourceId, int dimension, String metric) {
        if (dimension <= 0 || dimension > MAX_DIMENSION) {
            throw new IllegalArgumentException("HNSW index dimension must be between 1 and " + MAX_DIMENSION + ", got: " + dimension);
        }
        String opsClass = switch (metric != null ? metric : "cosine") {
            case "l2" -> "vector_l2_ops";
            case "dot" -> "vector_ip_ops";
            default -> "vector_cosine_ops";
        };

        String indexName = indexName(dataSourceId);

        // The invalid-index trap. CREATE INDEX CONCURRENTLY that fails part-way (a
        // dimension-cast error on a stray row, a lost connection, a killed build)
        // leaves an INVALID index that still bears this name. IF NOT EXISTS then
        // sees the corpse on every later attempt and does nothing, so the
        // datasource sequential-scans forever, with no error anywhere: the
        // measured 153x penalty, permanently, silently. isIndexValid existed for
        // this and had no callers.
        IndexState state = indexState(dataSourceId);
        if (state == IndexState.VALID) {
            log.debug("HNSW index {} already valid, nothing to build", indexName);
            return;
        }
        if (state == IndexState.INVALID) {
            log.warn("HNSW index {} exists but is INVALID (a previous build failed) - dropping it "
                    + "concurrently before rebuilding", indexName);
            dropHnswIndex(dataSourceId);
        }

        String sql = String.format(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON %s " +
            "USING hnsw ((embedding::vector(%d)) %s) WHERE data_source_id = %d",
            indexName, VECTOR_TABLE, dimension, opsClass, dataSourceId
        );

        log.info("Creating HNSW index {} (dim={}, metric={})", indexName, dimension, metric);
        jdbcTemplate.getJdbcTemplate().execute(sql);
        log.info("HNSW index {} created successfully", indexName);
    }

    /**
     * Drop the HNSW index for a specific datasource.
     *
     * <p><b>CONCURRENTLY, and therefore outside any transaction.</b> A plain
     * {@code DROP INDEX} takes ACCESS EXCLUSIVE on {@code data_source_vectors},
     * which is the table SHARED by every tenant's vectors: it would stall every
     * vector read and write on the platform while it waited behind any long
     * query. Wired to datasource deletion, that turns "delete my table" into a
     * platform-wide pause. {@code CONCURRENTLY} takes only SHARE UPDATE EXCLUSIVE
     * and cannot run inside a transaction block, hence NOT_SUPPORTED, exactly like
     * {@link #createHnswIndex}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dropHnswIndex(Long dataSourceId) {
        String indexName = indexName(dataSourceId);
        String sql = "DROP INDEX CONCURRENTLY IF EXISTS " + indexName;
        log.info("Dropping HNSW index {} (concurrently)", indexName);
        jdbcTemplate.getJdbcTemplate().execute(sql);
    }

    /** Where the per-datasource partial index stands. */
    public enum IndexState { MISSING, VALID, INVALID }

    /**
     * The state of a datasource's HNSW index. Distinguishes "no index" from "an
     * index that a failed CONCURRENTLY build left behind as INVALID": the two
     * need opposite handling, and collapsing them onto a boolean is what let the
     * invalid-index trap go unnoticed.
     */
    public IndexState indexState(Long dataSourceId) {
        // Anchored on the table's OID, not on the name alone: pg_class.relname is
        // not schema-qualified, and the one database holds every schema, so a
        // same-named leftover in public could otherwise be the row read here. If
        // THAT one were invalid, every build would drop the real, valid index and
        // rebuild it.
        String sql = "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "WHERE i.indrelid = '" + VECTOR_TABLE + "'::regclass AND c.relname = :index_name";
        List<Boolean> rows = jdbcTemplate.queryForList(sql,
                new MapSqlParameterSource("index_name", indexName(dataSourceId)), Boolean.class);
        if (rows.isEmpty()) {
            return IndexState.MISSING;
        }
        return Boolean.TRUE.equals(rows.get(0)) ? IndexState.VALID : IndexState.INVALID;
    }

    /**
     * Whether the datasource's HNSW index exists AND is usable. Kept for callers
     * that only need the boolean; prefer {@link #indexState} when the difference
     * between missing and invalid matters, which it does for any rebuild logic.
     */
    public boolean isIndexValid(Long dataSourceId) {
        return indexState(dataSourceId) == IndexState.VALID;
    }

    /** Number of per-datasource HNSW indexes on the shared table: the operational ceiling. */
    public int countHnswIndexes() {
        Integer n = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE i.indrelid = '" + VECTOR_TABLE + "'::regclass "
                        + "AND c.relname LIKE 'idx_vectors_ds_%'", Integer.class);
        return n != null ? n : 0;
    }

    private static String indexName(Long dataSourceId) {
        return "idx_vectors_ds_" + dataSourceId;
    }

    /** Maximum supported vector dimension (pgvector HNSW limit). */
    public static final int MAX_DIMENSION = 2000;

    /**
     * Convert float array to pgvector literal format: '[0.1,0.2,0.3]'
     * Validates that vector is non-empty and contains only finite values.
     */
    private String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Vector must not be null or empty");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("Vector element at index " + i + " is not finite: " + vector[i]);
            }
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    public record VectorEntry(Long itemId, String columnName, float[] embedding) {}
}
