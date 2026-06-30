package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.dto.*;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceColumnRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for executing CRUD operations on datasources.
 * Orchestrates between request validation, datasource verification, and repository operations.
 *
 * Follows Single Responsibility Principle - handles only CRUD execution orchestration.
 */
@Service
public class CrudExecutorService {

    private static final Logger log = LoggerFactory.getLogger(CrudExecutorService.class);

    /**
     * Service-layer cap on the per-request page size for read-row operations.
     * The repository no longer enforces a 101-row ceiling (the legacy
     * limit+1-trick default that silently truncated count-style nodes asking
     * for 10 000 rows). Service-layer cap = 10 000: at ~2 KB JSONB / row that
     * caps a single response at ~20 MB heap, well under any reasonable
     * request budget. Larger needs must paginate via offset.
     *
     * <p>Held at 10_000 deliberately: an earlier 2026-05-23 patch tried 1_000 and would
     * have silently truncated existing prod workflows that hardcoded {@code limit=10_000}
     * (e.g. the Daily Email Digest pattern flagged in {@code CrudRepository.java:154}).
     * Heap pressure on the render path is bounded by {@code OrchestratorLimitsConfig}
     * downstream (max rows per resolved variable, payload bytes); raw CRUD reads are
     * not the bottleneck for the 2026-05-22 OOM scenario.
     */
    private static final int MAX_READ_LIMIT = 10_000;

    private final CrudRepository crudRepository;
    private final VectorRepository vectorRepository;
    private final DataSourceService dataSourceService;
    private final StorageBreakdownService breakdownService;
    private final ColumnValueCoercer columnValueCoercer;
    private final DataSourceColumnRepository columnRepository;
    private final SqlSanitizer sqlSanitizer;
    private final DatasourceRowEventPublisher rowEventPublisher;
    private final com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public CrudExecutorService(CrudRepository crudRepository, VectorRepository vectorRepository,
                               DataSourceService dataSourceService,
                               StorageBreakdownService breakdownService, ColumnValueCoercer columnValueCoercer,
                               DataSourceColumnRepository columnRepository, SqlSanitizer sqlSanitizer,
                               DatasourceRowEventPublisher rowEventPublisher,
                               com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate,
                               org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.crudRepository = crudRepository;
        this.vectorRepository = vectorRepository;
        this.dataSourceService = dataSourceService;
        this.breakdownService = breakdownService;
        this.columnValueCoercer = columnValueCoercer;
        this.columnRepository = columnRepository;
        this.sqlSanitizer = sqlSanitizer;
        this.rowEventPublisher = rowEventPublisher;
        this.vectorFeatureGate = vectorFeatureGate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Execute a CRUD operation (legacy 2-arg path).
     *
     * <p>Back-compat shim that delegates with a null active organization
     * scope so callers that have not yet wired {@code X-Organization-ID}
     * keep working. New callers should pass the active workspace orgId so
     * the service-layer assertion in {@link #verifyDataSourceAccess(Long, String, String)}
     * closes cross-workspace bleed defense-in-depth.
     */
    @Transactional
    public CrudResult execute(CrudRequest request, String tenantId) {
        return execute(request, tenantId, null);
    }

    /**
     * Execute a CRUD operation with explicit workspace scope.
     *
     * @param request         The CRUD request
     * @param tenantId        The tenant ID (from X-User-ID header)
     * @param organizationId  Active workspace org id (from X-Organization-ID
     *                        header). When non-null the service layer
     *                        enforces strict org match on the resolved data
     *                        source - closes the cross-tenant bleed within a
     *                        shared personal-org bucket.
     */
    @Transactional
    public CrudResult execute(CrudRequest request, String tenantId, String organizationId) {
        log.info("Executing CRUD operation: {} for tenant: {} org: {}",
                request.getOperation(), tenantId, organizationId);

        // Verify datasource exists, belongs to tenant, and (when supplied)
        // matches the active organization scope.
        Long dataSourceId = request.getDataSourceId();
        DataSource dataSource = verifyDataSourceAccess(dataSourceId, tenantId, organizationId);

        // Item-row invariant: data_source_items.tenant_id always carries the OWNER's
        // tenantId (= data_sources.tenant_id), never the caller's. The UI path
        // (DataSourceEnhancedService.resolveAccessibleTenantId) already swaps to the
        // owner; this path previously stamped the workflow executor / org teammate's
        // tenant, leaving rows invisible to every owner-tenant-scoped query (empty
        // grid while the writer's own reads still saw them). V333 realigned the
        // drifted rows; this swap keeps new writes - and the reads, snapshots,
        // mapping-spec loads and storage accounting below - on the owner scope.
        String ownerTenantId = dataSource.tenantId();

        try {
            return switch (request) {
                case CreateRowRequest createReq -> executeCreateRow(createReq, dataSource, ownerTenantId);
                case CreateColumnRequest createColReq -> executeCreateColumn(createColReq, dataSource, ownerTenantId);
                case ReadRowRequest readReq -> executeReadRow(readReq, dataSource, ownerTenantId);
                case UpdateRowRequest updateReq -> executeUpdateRow(updateReq, dataSource, ownerTenantId);
                case DeleteRowRequest deleteReq -> executeDeleteRow(deleteReq, dataSource, ownerTenantId);
            };
        } catch (IllegalArgumentException e) {
            log.warn("CRUD operation failed with validation error: {}", e.getMessage());
            return CrudResult.failure(request.getOperation(), e.getMessage());
        } catch (Exception e) {
            log.error("CRUD operation failed with error: {}", e.getMessage(), e);
            return CrudResult.failure(request.getOperation(), "Operation failed: " + e.getMessage());
        }
    }

    /**
     * Execute create-row operation.
     */
    private CrudResult executeCreateRow(CreateRowRequest request, DataSource dataSource, String tenantId) {
        if (request.getRows() == null || request.getRows().isEmpty()) {
            return CrudResult.failure(CrudOperation.CREATE_ROW, "No rows to insert");
        }

        // Identify vector columns from mappingSpec
        Map<String, ColumnMappingSpec> vectorColumns = getVectorColumns(dataSource);

        // Edition gate (managed cloud): vector columns should not exist here -
        // every creation path rejects them - but a legacy/cloned table could
        // still carry one. Writing scalar rows to such a table stays allowed;
        // supplying a VALUE for a vector column is refused explicitly rather
        // than silently dropped or stored as a float[] blob in the JSONB.
        if (!vectorColumns.isEmpty() && !vectorFeatureGate.isVectorAllowed()) {
            for (CreateRowRequest.RowData row : request.getRows()) {
                if (row.columns() == null) continue;
                for (String vecCol : vectorColumns.keySet()) {
                    if (row.columns().get(vecCol) != null) {
                        return CrudResult.failure(CrudOperation.CREATE_ROW,
                            com.apimarketplace.datasource.services.VectorFeatureGate.DISABLED_MESSAGE);
                    }
                }
            }
            vectorColumns = Map.of(); // no values supplied → plain scalar insert
        }

        // Extract vector data from rows before coercion (coercion converts to float[])
        // We need to separate vector columns from scalar columns for different storage paths
        List<Map<String, float[]>> vectorDataPerRow = new ArrayList<>();
        if (!vectorColumns.isEmpty()) {
            for (CreateRowRequest.RowData row : request.getRows()) {
                Map<String, float[]> vectorData = new LinkedHashMap<>();
                if (row.columns() != null) {
                    for (String vecCol : vectorColumns.keySet()) {
                        Object val = row.columns().get(vecCol);
                        if (val != null) {
                            // Will be coerced to float[] by coerceRowValues below
                            vectorData.put(vecCol, null); // placeholder, filled after coercion
                        }
                    }
                }
                vectorDataPerRow.add(vectorData);
            }
        }

        // Coerce values per column type before insert (this converts vector values to float[])
        List<String> warnings = coerceRowValues(request.getRows(), dataSource, tenantId);

        // After coercion, extract the float[] values and remove vector columns from JSONB data
        if (!vectorColumns.isEmpty()) {
            for (int i = 0; i < request.getRows().size(); i++) {
                Map<String, Object> columns = request.getRows().get(i).columns();
                Map<String, float[]> vectorData = vectorDataPerRow.get(i);
                if (columns != null) {
                    for (String vecCol : vectorColumns.keySet()) {
                        Object val = columns.get(vecCol);
                        if (val instanceof float[] floats) {
                            // Fail fast on dimension mismatch. Without this, a
                            // wrong-size embedding stores silently and only
                            // breaks at query time ("dimension mismatch") -
                            // and a mixed-size corpus also breaks the HNSW
                            // index build. The expected size is the column's
                            // declared display.dimension.
                            int expected = declaredDimension(vectorColumns.get(vecCol));
                            if (expected > 0 && floats.length != expected) {
                                return CrudResult.failure(CrudOperation.CREATE_ROW, String.format(
                                    "Row %d: vector column '%s' expects dimension %d but got %d. "
                                        + "Re-embed with the model that produces %d-dimensional vectors, "
                                        + "or recreate the column with the right display.dimension.",
                                    i, vecCol, expected, floats.length, expected));
                            }
                            vectorData.put(vecCol, floats);
                            columns.remove(vecCol); // Don't store in JSONB
                        }
                    }
                }
            }
        }

        List<Long> insertedIds = crudRepository.createRows(
            dataSource.id(),
            tenantId,
            request.getRows()
        );

        // Batch insert vectors into the separate vector table
        if (!vectorColumns.isEmpty() && insertedIds.size() == vectorDataPerRow.size()) {
            List<VectorRepository.VectorEntry> entries = new ArrayList<>();
            for (int i = 0; i < insertedIds.size(); i++) {
                Long itemId = insertedIds.get(i);
                Map<String, float[]> vectorData = vectorDataPerRow.get(i);
                for (Map.Entry<String, float[]> entry : vectorData.entrySet()) {
                    if (entry.getValue() != null) {
                        entries.add(new VectorRepository.VectorEntry(itemId, entry.getKey(), entry.getValue()));
                    }
                }
            }
            if (!entries.isEmpty()) {
                vectorRepository.insertVectorBatch(dataSource.id(), tenantId, entries);
            }
        }

        // Track storage: estimate size of inserted rows
        long rowsSize = estimateRowDataSize(request.getRows());
        breakdownService.increment(tenantId, "DATATABLES", rowsSize, insertedIds.size());

        // Emit row_created events - picked up AFTER_COMMIT by DatasourceRowEventListener
        // and dispatched to trigger-service (fire-and-forget). Published before the commit
        // so each new row fans out to every matching datasource trigger subscription.
        publishCreatedEvents(dataSource.id(), tenantId, dataSource.organizationId(), insertedIds);

        String message = String.format("Created %d row(s) in datasource '%s'", insertedIds.size(), dataSource.name());
        if (!warnings.isEmpty()) {
            message += " [warnings: " + String.join("; ", warnings) + "]";
        }

        return CrudResult.success(
            CrudOperation.CREATE_ROW,
            message,
            CrudResult.ResultData.forCreate(insertedIds, warnings.isEmpty() ? null : warnings)
        );
    }

    /**
     * Execute create-column operation.
     * Also updates the datasource's mappingSpec to persist the new columns in the schema.
     */
    private CrudResult executeCreateColumn(CreateColumnRequest request, DataSource dataSource, String tenantId) {
        if (request.getColumns() == null || request.getColumns().isEmpty()) {
            return CrudResult.failure(CrudOperation.CREATE_COLUMN, "No columns to create");
        }

        // Type-level fail-fast: reserved names, valid type, and display contract for
        // select / multi_select / vector. Same chokepoint as agent tool + UI add_column
        // - workflow CRUD nodes that create columns via this path must obey the same
        // contract or the persisted mapping_spec ends up half-broken.
        for (CreateColumnRequest.ColumnDefinition col : request.getColumns()) {
            String validationError = com.apimarketplace.datasource.tools.datasource.ToolParameterUtils
                .validateColumnDefinition(col.name(), col.type(), col.display(),
                    vectorFeatureGate.isVectorAllowed());
            if (validationError != null) {
                return CrudResult.failure(CrudOperation.CREATE_COLUMN, validationError);
            }
        }

        List<String> createdColumns = crudRepository.createColumns(
            dataSource.id(),
            tenantId,
            request.getColumns()
        );

        // CRITICAL: Also update the datasource's mappingSpec so columns appear in schema
        // Without this, the columns exist in data but not in the schema metadata
        try {
            Map<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> currentSpec =
                dataSource.mappingSpec() != null
                    ? new java.util.LinkedHashMap<>(dataSource.mappingSpec())
                    : new java.util.LinkedHashMap<>();

            for (CreateColumnRequest.ColumnDefinition col : request.getColumns()) {
                String columnName = col.name();
                // Convert type string to ColumnType enum
                com.apimarketplace.datasource.domain.ColumnType columnType =
                    com.apimarketplace.datasource.domain.ColumnType.fromValue(
                        col.type() != null ? col.type() : "text"
                    );

                Map<String, Object> displayConfig = col.display() != null ? col.display() : Map.of();
                com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec colSpec =
                    new com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec(
                        columnName,                                    // path
                        columnType,                                    // type
                        com.apimarketplace.datasource.domain.ColumnStructure.SCALAR, // structure
                        Map.of(),                                      // children
                        displayConfig                                  // display
                    );
                currentSpec.put(columnName, colSpec);
            }

            // Persist the updated mappingSpec
            dataSourceService.updateMappingSpec(dataSource.id(), currentSpec);
            log.info("Updated mappingSpec with {} new columns for datasource {}", createdColumns.size(), dataSource.id());

            // Append each new column to column_order so they appear in the frontend
            for (String colName : createdColumns) {
                columnRepository.appendToColumnOrder(dataSource.id(), tenantId, colName);
            }

            // Schedule the HNSW index build for new vector columns. Fired as a
            // post-commit async event - CREATE INDEX CONCURRENTLY would wait on
            // this very transaction if invoked inline (self-deadlock).
            for (CreateColumnRequest.ColumnDefinition col : request.getColumns()) {
                if (col.type() != null && "vector".equalsIgnoreCase(col.type().trim())) {
                    publishVectorIndexEvent(dataSource.id(), col.display());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to update mappingSpec/columnOrder for datasource {}: {}. Columns were still added to data.",
                dataSource.id(), e.getMessage());
            // Don't fail the operation - columns were added to data, just not to schema
        }

        return CrudResult.success(
            CrudOperation.CREATE_COLUMN,
            String.format("Created %d column(s) in datasource '%s'", createdColumns.size(), dataSource.name()),
            CrudResult.ResultData.forCreateColumn(createdColumns)
        );
    }

    /**
     * Execute read-row operation.
     */
    private CrudResult executeReadRow(ReadRowRequest request, DataSource dataSource, String tenantId) {
        // Route to similarity search if similarity query is present
        if (request.getSimilarity() != null) {
            return executeSimilaritySearch(request, dataSource, tenantId);
        }

        WhereCondition where = null;
        if (request.getWhere() != null) {
            where = request.getWhere().toDomain();
            // Validate at the service layer (not inside the @Repository), so Spring's
            // PersistenceExceptionTranslationInterceptor doesn't re-wrap the IAE as an
            // InvalidDataAccessApiUsageException - which would bypass the IllegalArgumentException
            // catch below and fall through to the generic Exception branch that logs ERROR+stack
            // for what is really a user input error. The repo keeps its own validate() as a safety
            // net for any direct caller, but this path catches it cleanly first.
            where.validate();
        }

        // Service-layer page-size cap: the caller can ask for up to MAX_READ_LIMIT
        // rows (10 000). The repo no longer applies its own legacy 101 ceiling
        // (see CrudRepository.readRows comment for context). 10 000 keeps the
        // worst-case per-request heap footprint bounded - at ~2 KB JSONB per
        // row on a realistic datasource that's ~20 MB, well under any tomcat
        // request budget. Above this the caller should paginate via offset.
        int requestedLimit = request.getLimit() != null ? request.getLimit() : 20;
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_READ_LIMIT);
        int offset = request.getOffset() != null ? request.getOffset() : 0;

        // Fetch limit+1 rows to detect if more exist
        List<Map<String, Object>> rawRows = crudRepository.readRows(
            dataSource.id(),
            tenantId,
            where,
            limit + 1,
            offset
        );

        boolean hasMore = rawRows.size() > limit;
        List<Map<String, Object>> rows = hasMore ? rawRows.subList(0, limit) : rawRows;

        // Flatten the data JSONB column into top-level fields
        List<Map<String, Object>> flattenedRows = flattenRows(rows);

        return CrudResult.success(
            CrudOperation.READ_ROW,
            String.format("Read %d row(s) from datasource '%s'", flattenedRows.size(), dataSource.name()),
            CrudResult.ResultData.forRead(flattenedRows, hasMore, offset)
        );
    }

    /**
     * Execute a vector similarity search, optionally combined with WHERE filters (hybrid search).
     */
    private CrudResult executeSimilaritySearch(ReadRowRequest request, DataSource dataSource, String tenantId) {
        // Edition gate (managed cloud): defense in depth for the read side -
        // even a legacy/cloned table with stored vectors is not searchable.
        if (!vectorFeatureGate.isVectorAllowed()) {
            return CrudResult.failure(CrudOperation.READ_ROW,
                com.apimarketplace.datasource.services.VectorFeatureGate.DISABLED_MESSAGE);
        }

        SimilarityQueryDto similarity = request.getSimilarity();

        // Resolve vector column config from mappingSpec
        Map<String, ColumnMappingSpec> mappingSpec = dataSource.mappingSpec();
        if (mappingSpec == null || !mappingSpec.containsKey(similarity.getColumn())) {
            return CrudResult.failure(CrudOperation.READ_ROW,
                "Vector column '" + similarity.getColumn() + "' not found in datasource schema");
        }

        ColumnMappingSpec colSpec = mappingSpec.get(similarity.getColumn());
        if (colSpec.type() != ColumnType.VECTOR) {
            return CrudResult.failure(CrudOperation.READ_ROW,
                "Column '" + similarity.getColumn() + "' is not a vector column");
        }

        // Get dimension and metric from column display config
        Map<String, Object> display = colSpec.display() != null ? colSpec.display() : Map.of();
        int dimension = display.get("dimension") instanceof Number n ? n.intValue() : 0;
        String metric = display.get("metric") instanceof String s ? s : "cosine";

        if (dimension <= 0) {
            return CrudResult.failure(CrudOperation.READ_ROW,
                "Vector column '" + similarity.getColumn() + "' has no dimension configured");
        }

        // Validate query vector dimension
        if (similarity.getQueryVector().length != dimension) {
            return CrudResult.failure(CrudOperation.READ_ROW,
                String.format("Query vector dimension mismatch: expected %d, got %d",
                    dimension, similarity.getQueryVector().length));
        }

        int topK = similarity.getTopK() != null ? similarity.getTopK() : 5;

        // Build optional WHERE clause for hybrid search
        String whereClause = null;
        MapSqlParameterSource extraParams = null;
        if (request.getWhere() != null) {
            WhereCondition where = request.getWhere().toDomain();
            where.validate();
            extraParams = new MapSqlParameterSource();
            whereClause = buildWhereClauseForSimilarity(where, extraParams);
        }

        List<Map<String, Object>> rawRows = vectorRepository.similaritySearch(
            dataSource.id(), tenantId, similarity.getColumn(),
            similarity.getQueryVector(), dimension, metric,
            topK, similarity.getThreshold(),
            whereClause, extraParams
        );

        // Flatten rows and include distance score
        List<Map<String, Object>> flattenedRows = flattenRows(rawRows);

        return CrudResult.success(
            CrudOperation.READ_ROW,
            String.format("Found %d similar row(s) in datasource '%s'", flattenedRows.size(), dataSource.name()),
            CrudResult.ResultData.forRead(flattenedRows, false, 0)
        );
    }

    /**
     * Build a WHERE clause fragment for hybrid search (similarity + JSONB filter).
     * Operates on the items table alias 'i'.
     */
    private String buildWhereClauseForSimilarity(WhereCondition where, MapSqlParameterSource params) {
        String column = sqlSanitizer.sanitizeColumnName(where.column());
        String operator = where.getNormalizedOperator();
        Object value = where.value();

        String paramName = "sim_where_val";
        params.addValue(paramName, value);

        // Use jsonb_extract_path_text for safe parameterized JSONB access
        params.addValue("sim_where_col", column);
        return String.format("jsonb_extract_path_text(i.data, :sim_where_col) %s :%s", operator, paramName);
    }

    /**
     * Execute update-row operation.
     */
    private CrudResult executeUpdateRow(UpdateRowRequest request, DataSource dataSource, String tenantId) {
        if (request.getWhere() == null) {
            return CrudResult.failure(CrudOperation.UPDATE_ROW, "WHERE condition is required for update");
        }
        if (request.getSet() == null || request.getSet().isEmpty()) {
            return CrudResult.failure(CrudOperation.UPDATE_ROW, "No columns to update");
        }

        // Edition gate (managed cloud): update_rows is a vector WRITE path -
        // setting a vector column's value would store an embedding, the exact
        // operation the create-row gate blocks. Same rule, same message:
        // scalar updates on a legacy vector table stay allowed; supplying a
        // vector VALUE is refused before any coercion or DML.
        Map<String, ColumnMappingSpec> vectorColumns = getVectorColumns(dataSource);
        if (!vectorColumns.isEmpty() && !vectorFeatureGate.isVectorAllowed()) {
            for (String vecCol : vectorColumns.keySet()) {
                if (request.getSet().get(vecCol) != null) {
                    return CrudResult.failure(CrudOperation.UPDATE_ROW,
                        com.apimarketplace.datasource.services.VectorFeatureGate.DISABLED_MESSAGE);
                }
            }
            vectorColumns = Map.of(); // no vector values supplied → plain scalar update
        }

        // Coerce update values per column type
        List<String> warnings = coerceUpdateValues(request.getSet(), dataSource, tenantId);

        // Extract vector columns from set map - they can't go into JSONB
        Map<String, float[]> vectorUpdates = new LinkedHashMap<>();
        if (!vectorColumns.isEmpty()) {
            for (Map.Entry<String, ColumnMappingSpec> vecEntry : vectorColumns.entrySet()) {
                String vecCol = vecEntry.getKey();
                Object val = request.getSet().get(vecCol);
                if (val instanceof float[] floats) {
                    // Same insert-time dimension fail-fast as create-row: a
                    // wrong-size embedding stored via update breaks the HNSW
                    // index and only surfaces at query time otherwise.
                    int expected = declaredDimension(vecEntry.getValue());
                    if (expected > 0 && floats.length != expected) {
                        return CrudResult.failure(CrudOperation.UPDATE_ROW, String.format(
                            "Vector column '%s' expects dimension %d but got %d. "
                                + "Re-embed with the model that produces %d-dimensional vectors, "
                                + "or recreate the column with the right display.dimension.",
                            vecCol, expected, floats.length, expected));
                    }
                    vectorUpdates.put(vecCol, floats);
                    request.getSet().remove(vecCol);
                }
            }
        }

        WhereCondition where = request.getWhere().toDomain();
        where.validate(); // validate before @Repository so IAE isn't translated (see executeReadRow)

        // Capture the id list + pre-update snapshots BEFORE the update runs, so
        // event emission can expose `previous_row` for each row_updated event.
        // We resolve ids independently of vectorUpdates because events always need them.
        List<Long> affectedItemIds = crudRepository.findIdsMatching(dataSource.id(), tenantId, where);
        Map<Long, Map<String, Object>> beforeSnapshots = snapshotRowsById(dataSource.id(), tenantId, affectedItemIds);

        int affectedRows;
        if (request.getSet().isEmpty()) {
            // Only vector columns were in the set - no JSONB update needed
            affectedRows = affectedItemIds.size();
        } else {
            affectedRows = crudRepository.updateRows(
                dataSource.id(),
                tenantId,
                where,
                request.getSet()
            );
        }

        // Batch upsert vectors for affected rows
        if (!vectorUpdates.isEmpty() && !affectedItemIds.isEmpty()) {
            List<VectorRepository.VectorEntry> entries = new ArrayList<>();
            for (Long itemId : affectedItemIds) {
                for (Map.Entry<String, float[]> entry : vectorUpdates.entrySet()) {
                    entries.add(new VectorRepository.VectorEntry(itemId, entry.getKey(), entry.getValue()));
                }
            }
            vectorRepository.insertVectorBatch(dataSource.id(), tenantId, entries);
        }

        // Emit row_updated events - uses the pre-captured before snapshots plus
        // a fresh read of the current row state for `row`.
        publishUpdatedEvents(dataSource.id(), tenantId, dataSource.organizationId(), affectedItemIds, beforeSnapshots);

        String message = String.format("Updated %d row(s) in datasource '%s'", affectedRows, dataSource.name());
        if (!warnings.isEmpty()) {
            message += " [warnings: " + String.join("; ", warnings) + "]";
        }

        return CrudResult.success(
            CrudOperation.UPDATE_ROW,
            message,
            CrudResult.ResultData.forUpdate(affectedRows, warnings.isEmpty() ? null : warnings)
        );
    }

    /**
     * Execute delete-row operation.
     */
    private CrudResult executeDeleteRow(DeleteRowRequest request, DataSource dataSource, String tenantId) {
        if (request.getWhere() == null) {
            return CrudResult.failure(CrudOperation.DELETE_ROW, "WHERE condition is required for delete");
        }

        WhereCondition where = request.getWhere().toDomain();
        where.validate(); // validate before @Repository so IAE isn't translated (see executeReadRow)

        // Capture last-known snapshots BEFORE the delete so row_deleted events can
        // expose `row` = pre-delete state (the row is gone after the DML runs).
        List<Long> affectedIds = crudRepository.findIdsMatching(dataSource.id(), tenantId, where);
        Map<Long, Map<String, Object>> lastKnown = snapshotRowsById(dataSource.id(), tenantId, affectedIds);

        int deletedRows = crudRepository.deleteRows(
            dataSource.id(),
            tenantId,
            where
        );

        // Track storage: decrement count (size corrected by reconciliation)
        if (deletedRows > 0) {
            breakdownService.increment(tenantId, "DATATABLES", 0, -deletedRows);
        }

        publishDeletedEvents(dataSource.id(), tenantId, dataSource.organizationId(), affectedIds, lastKnown);

        return CrudResult.success(
            CrudOperation.DELETE_ROW,
            String.format("Deleted %d row(s) from datasource '%s'", deletedRows, dataSource.name()),
            CrudResult.ResultData.forDelete(deletedRows)
        );
    }

    /**
     * Flatten rows by merging the "data" JSONB column into the top-level row.
     * Input: [{id, data: {name, email}, priority, created_at}]
     * Output: [{id, name, email, priority, created_at}]
     */
    private List<Map<String, Object>> flattenRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::flattenRow).toList();
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenRow(Map<String, Object> row) {
        Map<String, Object> flat = new LinkedHashMap<>();
        Object dataObj = row.get("data");

        Map<String, Object> dataMap = null;
        if (dataObj instanceof Map) {
            dataMap = (Map<String, Object>) dataObj;
        } else if (dataObj != null) {
            // JSONB comes back as PGobject or String from JDBC - parse it
            String jsonStr = dataObj.toString();
            try {
                dataMap = objectMapper.readValue(jsonStr, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse data JSONB for row: {}", e.getMessage());
            }
        }

        if (dataMap != null) {
            flat.putAll(dataMap);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!"data".equals(entry.getKey())) {
                flat.put(entry.getKey(), entry.getValue());  // system cols override on collision
            }
        }
        return flat;
    }

    /**
     * Get vector columns from a datasource's mappingSpec.
     */
    private Map<String, ColumnMappingSpec> getVectorColumns(DataSource dataSource) {
        Map<String, ColumnMappingSpec> result = new LinkedHashMap<>();
        if (dataSource.mappingSpec() != null) {
            for (Map.Entry<String, ColumnMappingSpec> entry : dataSource.mappingSpec().entrySet()) {
                if (entry.getValue().type() == ColumnType.VECTOR) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    /** Declared {@code display.dimension} of a vector column spec, or 0 when absent. */
    private static int declaredDimension(ColumnMappingSpec spec) {
        if (spec == null || spec.display() == null) return 0;
        Object dim = spec.display().get("dimension");
        if (dim instanceof Number n) return n.intValue();
        if (dim instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    /**
     * Publish the post-commit HNSW build event for a freshly created vector
     * column. Dimension comes from the validated {@code display.dimension}
     * (required by ToolParameterUtils); a missing/invalid one is skipped -
     * the search path still works via seq scan.
     */
    private void publishVectorIndexEvent(Long dataSourceId, Map<String, Object> display) {
        Map<String, Object> d = display != null ? display : Map.of();
        Object dimRaw = d.get("dimension");
        int dimension = dimRaw instanceof Number n ? n.intValue() : 0;
        if (dimension <= 0 && dimRaw instanceof String s) {
            try { dimension = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        if (dimension <= 0) {
            log.warn("Vector column created without usable dimension on datasource {} - HNSW index skipped", dataSourceId);
            return;
        }
        String metric = d.get("metric") instanceof String m ? m : "cosine";
        eventPublisher.publishEvent(
            new com.apimarketplace.datasource.events.VectorColumnCreatedEvent(dataSourceId, dimension, metric));
    }

    private long estimateRowDataSize(List<CreateRowRequest.RowData> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        long total = 0;
        for (CreateRowRequest.RowData row : rows) {
            if (row.columns() != null) {
                try {
                    total += objectMapper.writeValueAsBytes(row.columns()).length;
                } catch (Exception e) {
                    // skip
                }
            }
        }
        return total;
    }

    // ==================== Event emission helpers ====================

    /**
     * Snapshot rows by id, returning a map id → flattened row. Used by UPDATE/DELETE
     * to capture before-state for trigger events.
     */
    private Map<Long, Map<String, Object>> snapshotRowsById(Long dataSourceId, String tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> raw : crudRepository.findRowsByIds(dataSourceId, tenantId, ids)) {
            Long id = toLong(raw.get("id"));
            if (id == null) continue;
            out.put(id, flattenRow(raw));
        }
        return out;
    }

    private void publishCreatedEvents(Long dataSourceId, String tenantId,
                                      String organizationId, List<Long> insertedIds) {
        if (rowEventPublisher == null || insertedIds == null || insertedIds.isEmpty()) return;
        Map<Long, Map<String, Object>> snapshots = snapshotRowsById(dataSourceId, tenantId, insertedIds);
        for (Long id : insertedIds) {
            Map<String, Object> row = snapshots.get(id);
            if (row == null) continue;
            try {
                rowEventPublisher.publishCreated(dataSourceId, id, tenantId, organizationId, row);
            } catch (Exception e) {
                log.warn("Failed to publish row_created event for datasource={} row={}: {}",
                        dataSourceId, id, e.getMessage());
            }
        }
    }

    private void publishUpdatedEvents(Long dataSourceId, String tenantId,
                                      String organizationId,
                                      List<Long> ids,
                                      Map<Long, Map<String, Object>> beforeSnapshots) {
        if (rowEventPublisher == null || ids == null || ids.isEmpty()) return;
        Map<Long, Map<String, Object>> afterSnapshots = snapshotRowsById(dataSourceId, tenantId, ids);
        for (Long id : ids) {
            Map<String, Object> after = afterSnapshots.get(id);
            Map<String, Object> before = beforeSnapshots.get(id);
            if (after == null) continue; // row no longer exists (concurrent delete) - skip
            try {
                rowEventPublisher.publishUpdated(dataSourceId, id, tenantId, organizationId, after, before);
            } catch (Exception e) {
                log.warn("Failed to publish row_updated event for datasource={} row={}: {}",
                        dataSourceId, id, e.getMessage());
            }
        }
    }

    private void publishDeletedEvents(Long dataSourceId, String tenantId,
                                      String organizationId,
                                      List<Long> ids,
                                      Map<Long, Map<String, Object>> lastKnown) {
        if (rowEventPublisher == null || ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            Map<String, Object> row = lastKnown.get(id);
            if (row == null) continue;
            try {
                rowEventPublisher.publishDeleted(dataSourceId, id, tenantId, organizationId, row);
            } catch (Exception e) {
                log.warn("Failed to publish row_deleted event for datasource={} row={}: {}",
                        dataSourceId, id, e.getMessage());
            }
        }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Coerce row values according to column types from mapping spec.
     * Returns list of warnings from coercion (empty if none).
     */
    private List<String> coerceRowValues(List<CreateRowRequest.RowData> rows, DataSource dataSource, String tenantId) {
        Map<String, ColumnMappingSpec> mappingSpec = loadMappingSpecSafe(dataSource, tenantId);
        if (mappingSpec.isEmpty()) return List.of();

        List<String> warnings = new ArrayList<>();
        for (CreateRowRequest.RowData row : rows) {
            if (row.columns() == null) continue;
            for (Map.Entry<String, Object> entry : row.columns().entrySet()) {
                ColumnMappingSpec spec = mappingSpec.get(entry.getKey());
                if (spec != null && spec.type() != null) {
                    CoercionResult result = columnValueCoercer.coerce(
                            entry.getValue(), spec.type(), spec.display());
                    failOnDroppedVector(spec, entry.getKey(), entry.getValue(), result);
                    entry.setValue(result.value());
                    result.warnings().forEach(w -> warnings.add(entry.getKey() + ": " + w));
                }
            }
        }
        return warnings;
    }

    /**
     * Promote a failed VECTOR coercion (dimension mismatch, non-numeric
     * element, …) from a warning to an explicit failure. For scalar columns
     * the historical degrade-to-null-with-warning behavior stands, but a
     * silently dropped embedding is data loss the caller cannot see: the row
     * inserts "successfully", the vector is missing, and similarity search
     * quietly skips it. No silent fallbacks on vector data.
     */
    private static void failOnDroppedVector(ColumnMappingSpec spec, String column,
                                            Object originalValue, CoercionResult result) {
        if (spec.type() == ColumnType.VECTOR && originalValue != null
                && result.value() == null && result.hasWarnings()) {
            throw new IllegalArgumentException(
                    "Vector column '" + column + "': " + String.join("; ", result.warnings()));
        }
    }

    /**
     * Coerce update SET values according to column types from mapping spec.
     * Returns list of warnings from coercion (empty if none).
     */
    private List<String> coerceUpdateValues(Map<String, Object> setValues, DataSource dataSource, String tenantId) {
        Map<String, ColumnMappingSpec> mappingSpec = loadMappingSpecSafe(dataSource, tenantId);
        if (mappingSpec.isEmpty()) return List.of();

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            ColumnMappingSpec spec = mappingSpec.get(entry.getKey());
            if (spec != null && spec.type() != null) {
                CoercionResult result = columnValueCoercer.coerce(
                        entry.getValue(), spec.type(), spec.display());
                failOnDroppedVector(spec, entry.getKey(), entry.getValue(), result);
                entry.setValue(result.value());
                result.warnings().forEach(w -> warnings.add(entry.getKey() + ": " + w));
            }
        }
        return warnings;
    }

    private Map<String, ColumnMappingSpec> loadMappingSpecSafe(DataSource dataSource, String tenantId) {
        try {
            return columnRepository.loadMappingSpec(dataSource.id(), tenantId);
        } catch (Exception e) {
            log.debug("Could not load mapping spec for coercion: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Verify that the datasource exists and belongs to the tenant.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @return The DataSource if found and accessible
     * @throws IllegalArgumentException if not found or not accessible
     */
    private DataSource verifyDataSourceAccess(Long dataSourceId, String tenantId) {
        return verifyDataSourceAccess(dataSourceId, tenantId, null);
    }

    private DataSource verifyDataSourceAccess(Long dataSourceId, String tenantId, String organizationId) {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("DataSource ID is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID is required");
        }

        Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(dataSourceId);

        if (dataSourceOpt.isEmpty()) {
            log.warn("DataSource not found: {}", dataSourceId);
            throw new IllegalArgumentException("DataSource not found: " + dataSourceId);
        }

        DataSource dataSource = dataSourceOpt.get();
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId,
                dataSource.tenantId(), dataSource.organizationId())) {
            log.warn("DataSource {} not in scope for tenant={} org={}",
                    dataSourceId, tenantId, organizationId);
            throw new IllegalArgumentException("DataSource not found: " + dataSourceId);
        }

        return dataSource;
    }
}
