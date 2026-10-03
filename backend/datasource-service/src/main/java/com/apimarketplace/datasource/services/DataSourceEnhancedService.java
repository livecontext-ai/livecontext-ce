package com.apimarketplace.datasource.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels;
import com.apimarketplace.datasource.domain.DataSourceModels;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.exception.ResourceNotFoundException;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import com.apimarketplace.datasource.services.DataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service layer for enhanced DataSource operations with server-side pagination,
 * filtering, sorting, and JSON patch operations
 *
 * Architecture: Controller → Service → Repository
 * Following SOLID principles and clean architecture
 */
@Service
@Transactional
public class DataSourceEnhancedService {

    /**
     * Maximum number of columns allowed per DataSource (excluding system columns)
     */
    private static final Logger log = LoggerFactory.getLogger(DataSourceEnhancedService.class);

    public static final int MAX_COLUMNS_PER_DATASOURCE = 30;

    private final DataSourceEnhancedRepositories repositories;
    private final VectorRepository vectorRepository;
    private final DataSourceService dataSourceService;
    private final DatasourceRowEventPublisher rowEventPublisher;
    private final com.apimarketplace.datasource.crud.service.MediaCellHydrator mediaCellHydrator;
    private final VectorFeatureGate vectorFeatureGate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public DataSourceEnhancedService(DataSourceEnhancedRepositories repositories,
                                     VectorRepository vectorRepository,
                                     DataSourceService dataSourceService,
                                     DatasourceRowEventPublisher rowEventPublisher,
                                     com.apimarketplace.datasource.crud.service.MediaCellHydrator mediaCellHydrator,
                                     VectorFeatureGate vectorFeatureGate,
                                     org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.repositories = repositories;
        this.vectorRepository = vectorRepository;
        this.dataSourceService = dataSourceService;
        this.rowEventPublisher = rowEventPublisher;
        this.mediaCellHydrator = mediaCellHydrator;
        this.vectorFeatureGate = vectorFeatureGate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Get items with server-side pagination, filtering, and sorting
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param request Pagination and filtering request
     * @return Paginated response with items
     */
    public PaginationResponse<DataSourceItemRow> getItemsWithPagination(
            Long dataSourceId,
            String tenantId,
            PaginationRequest request) {
        return getItemsWithPagination(dataSourceId, tenantId, null, null, request);
    }

    public PaginationResponse<DataSourceItemRow> getItemsWithPagination(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            PaginationRequest request) {
        
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || request == null) {
            throw new IllegalArgumentException("dataSourceId, tenantId, and request cannot be null");
        }
        String effectiveTenantId = resolveAccessibleTenantId(dataSourceId, tenantId, orgId, orgRole);
        
        // 🇫🇷 Verification de l'existence de la DataSource et de l'isolation tenant
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new ResourceNotFoundException("DataSource", dataSourceId);
        }
        
        // 🇫🇷 Calcul du nombre total d'elements avec les memes filtres
        int totalItems = repositories.countItemsWithFilters(dataSourceId, effectiveTenantId, request);
        
        // 🇫🇷 Calcul du nombre total de pages
        int totalPages = (int) Math.ceil((double) totalItems / request.limit());
        
        // 🇫🇷 Recuperation des items avec pagination
        List<DataSourceItemRow> items;
        String nextCursor = null;
        boolean hasMore = false;
        
        if (request.cursor() != null && !request.cursor().trim().isEmpty()) {
            // 🇫🇷 Pagination par curseur (navigation sequentielle)
            items = repositories.findItemsWithPagination(dataSourceId, effectiveTenantId, request);
            
            if (items.size() > request.limit()) {
                hasMore = true;
                items = items.subList(0, request.limit());
                
                // Generer le cursor pour la page suivante
                DataSourceItemRow lastItem = items.get(items.size() - 1);
                DataSourceEnhancedModels.KeysetCursor cursor = DataSourceEnhancedModels.KeysetCursor.of(
                    lastItem.createdAt(),
                    lastItem.id()
                );
                nextCursor = cursor.encode();
            }
        } else {
            // 🇫🇷 Pagination par offset (navigation directe)
            int offset = (request.startRow() != null ? request.startRow() : 0);
            items = repositories.findItemsWithOffset(dataSourceId, effectiveTenantId, request, offset);
            
            // Calculer si il y a une page suivante
            hasMore = (offset + request.limit()) < totalItems;
            if (hasMore) {
                // Generer un cursor pour la page suivante (simulation)
                if (!items.isEmpty()) {
                    DataSourceItemRow lastItem = items.get(items.size() - 1);
                    DataSourceEnhancedModels.KeysetCursor cursor = DataSourceEnhancedModels.KeysetCursor.of(
                        lastItem.createdAt(),
                        lastItem.id()
                    );
                    nextCursor = cursor.encode();
                }
            }
        }
        
        // Enrich items with vector previews if datasource has vector columns
        items = enrichWithVectorPreviews(items, dataSourceId, effectiveTenantId);

        return new PaginationResponse<>(
            items,
            totalItems,
            nextCursor,
            hasMore,
            totalPages
        );
    }

    /**
     * Enrich items with truncated vector previews from the separate vector table.
     * Vectors are stored outside JSONB to avoid bloat, but we inject a truncated
     * string representation for display in the data table.
     */
    private List<DataSourceItemRow> enrichWithVectorPreviews(
            List<DataSourceItemRow> items, Long dataSourceId, String tenantId) {
        if (items.isEmpty()) return items;

        // Check if this datasource has vector columns
        Set<String> vectorColumnNames = getVectorColumnNames(dataSourceId, tenantId);
        if (vectorColumnNames.isEmpty()) return items;

        // Get truncated vector previews for all item IDs in one query
        List<Long> itemIds = items.stream().map(DataSourceItemRow::id).toList();
        Map<Long, Map<String, String>> previews =
            vectorRepository.getVectorPreviewsForItems(itemIds, tenantId);
        if (previews.isEmpty()) return items;

        // Inject vector previews into item data
        List<DataSourceItemRow> enriched = new ArrayList<>(items.size());
        for (DataSourceItemRow item : items) {
            Map<String, String> itemVectors = previews.get(item.id());
            if (itemVectors != null && !itemVectors.isEmpty()) {
                Map<String, Object> enrichedData = new LinkedHashMap<>(item.data());
                for (Map.Entry<String, String> entry : itemVectors.entrySet()) {
                    enrichedData.put(entry.getKey(), entry.getValue());
                }
                enriched.add(new DataSourceItemRow(
                    item.id(), item.dataSourceId(), item.tenantId(),
                    enrichedData, item.priority(), item.createdAt(), item.updatedAt()
                ));
            } else {
                enriched.add(item);
            }
        }
        return enriched;
    }

    private Set<String> getVectorColumnNames(Long dataSourceId, String tenantId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        return dataSourceService.getDataSource(dataSourceId)
            .filter(ds -> ScopeGuard.isInStrictScope(tenantId, orgId,
                    ds.tenantId(), ds.organizationId()) && ds.mappingSpec() != null)
            .map(ds -> ds.mappingSpec().entrySet().stream()
                .filter(e -> e.getValue().type() == ColumnType.VECTOR)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet()))
            .orElse(Set.of());
    }
    
    /**
     * Get column definitions for dynamic table columns
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @return List of column definitions
     */
    public List<ColumnDefinition> getColumnDefinitions(Long dataSourceId, String tenantId) {
        return getColumnDefinitions(dataSourceId, tenantId, null, null);
    }

    public List<ColumnDefinition> getColumnDefinitions(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole) {
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null) {
            throw new IllegalArgumentException("dataSourceId and tenantId cannot be null");
        }
        String effectiveTenantId = resolveAccessibleTenantId(dataSourceId, tenantId, orgId, orgRole);
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        return repositories.getColumnDefinitions(dataSourceId, effectiveTenantId);
    }
    
    /**
     * Save column order for a DataSource
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param columnOrder List of column order mappings
     * @return true if successful
     */
    public boolean saveColumnOrder(Long dataSourceId, String tenantId, List<Map<String, Object>> columnOrder) {
        return saveColumnOrder(dataSourceId, tenantId, null, null, columnOrder);
    }

    public boolean saveColumnOrder(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            List<Map<String, Object>> columnOrder) {
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || columnOrder == null) {
            throw new IllegalArgumentException("dataSourceId, tenantId, and columnOrder cannot be null");
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);
        
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        return repositories.saveColumnOrder(dataSourceId, effectiveTenantId, columnOrder);
    }
    
    /**
     * Apply JSON patch operations to a single item
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param itemId ID of the item to update
     * @param patches List of JSON patch operations
     * @return Updated item
     */
    public DataSourceItemRow applyJsonPatch(
            Long dataSourceId,
            String tenantId,
            Long itemId,
            List<JsonPatchOperation> patches) {
        return applyJsonPatch(dataSourceId, tenantId, null, null, itemId, patches);
    }

    public DataSourceItemRow applyJsonPatch(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            Long itemId,
            List<JsonPatchOperation> patches) {

        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || itemId == null || patches == null) {
            throw new IllegalArgumentException("All parameters cannot be null");
        }

        if (patches.isEmpty()) {
            throw new IllegalArgumentException("Patches cannot be empty");
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // 🇫🇷 Verification de l'existence de l'item et de l'isolation tenant
        if (!repositories.itemExists(dataSourceId, effectiveTenantId, itemId)) {
            throw new IllegalArgumentException("Item not found or access denied");
        }

        // Capture pre-patch snapshot for trigger event `previous_row`
        Map<String, Object> beforeSnapshot = findFlattenedById(dataSourceId, effectiveTenantId, itemId);
        String sensitivity = sensitivitiesOf(dataSourceId, effectiveTenantId, List.of(itemId)).get(itemId);

        // 🇫🇷 Application des patches JSONB
        DataSourceItemRow updated = repositories.applyJsonPatch(dataSourceId, effectiveTenantId, itemId, patches);

        publishUpdatedEvent(dataSourceId, effectiveTenantId, updated, beforeSnapshot, sensitivity);
        return updated;
    }
    
    /**
     * Delete a single item
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param itemId ID of the item to delete
     */
    public void deleteItem(Long dataSourceId, String tenantId, Long itemId) {
        deleteItem(dataSourceId, tenantId, null, null, itemId);
    }

    public void deleteItem(Long dataSourceId, String tenantId, String orgId, String orgRole, Long itemId) {
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || itemId == null) {
            throw new IllegalArgumentException("All parameters cannot be null");
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // 🇫🇷 Verification de l'existence de l'item et de l'isolation tenant
        if (!repositories.itemExists(dataSourceId, effectiveTenantId, itemId)) {
            throw new IllegalArgumentException("Item not found or access denied");
        }

        // Capture last-known snapshot BEFORE delete so row_deleted exposes `row`
        Map<String, Object> lastKnown = findFlattenedById(dataSourceId, effectiveTenantId, itemId);
        String sensitivity = sensitivitiesOf(dataSourceId, effectiveTenantId, List.of(itemId)).get(itemId);

        // 🇫🇷 Suppression de l'item
        repositories.deleteItem(dataSourceId, effectiveTenantId, itemId);

        if (rowEventPublisher != null && lastKnown != null) {
            try {
                rowEventPublisher.publishDeleted(dataSourceId, itemId, effectiveTenantId,
                        orgIdOf(dataSourceForEvent(dataSourceId)), lastKnown, sensitivity);
            } catch (Exception e) {
                log.warn("Failed to publish row_deleted for datasource={} row={}: {}",
                        dataSourceId, itemId, e.getMessage());
            }
        }
    }
    
    /**
     * Execute bulk operations (delete or patch multiple items)
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param request Bulk operation request
     * @return Bulk operation result
     */
    public BulkOperationResult executeBulkOperation(
            Long dataSourceId,
            String tenantId,
            BulkOperationRequest request) {
        return executeBulkOperation(dataSourceId, tenantId, null, null, request);
    }

    public BulkOperationResult executeBulkOperation(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            BulkOperationRequest request) {

        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || request == null) {
            throw new IllegalArgumentException("All parameters cannot be null");
        }

        if (request.ids().isEmpty()) {
            throw new IllegalArgumentException("Item IDs cannot be empty");
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // 🇫🇷 Verification de l'existence de la DataSource
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        // Capture pre-op snapshots for ALL ids. We cannot tell after the fact which
        // rows were actually touched (the op name is only known here), so we snapshot
        // the full id list and match post-op state row by row for accurate events.
        Map<Long, Map<String, Object>> beforeSnapshots = snapshotFlattenedByIds(dataSourceId, effectiveTenantId, request.ids());
        Map<Long, String> sensitivities = sensitivitiesOf(dataSourceId, effectiveTenantId, request.ids());

        // 🇫🇷 Execution de l'operation bulk
        BulkOperationResult result = repositories.executeBulkOperation(dataSourceId, effectiveTenantId, request);

        publishBulkEvents(dataSourceId, effectiveTenantId, request, beforeSnapshots, sensitivities);
        return result;
    }
    
    /**
     * Manage columns (drop, rename, set default value)
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param request Column management request
     * @return Column management result
     */
    public ColumnManagementResult manageColumn(
            Long dataSourceId,
            String tenantId,
            ColumnManagementRequest request) {
        return manageColumn(dataSourceId, tenantId, null, null, request);
    }

    public ColumnManagementResult manageColumn(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            ColumnManagementRequest request) {

        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || request == null) {
            throw new IllegalArgumentException("All parameters cannot be null");
        }

        // RENAME: validate the new column name against reserved names BEFORE the
        // SQL UPDATE. Without this guard, renaming "title" → "id" (or "data",
        // "tenant_id", …) collides with the physical data_source_items columns
        // and silently shadows the user's value on read. Reserved-name list is
        // shared with create / add_columns via ToolParameterUtils so the contract
        // is uniform across every column-mutating path.
        // UPDATE_DISPLAY: the same atomic op may carry an optional new_key - if
        // present, apply the same reserved-name validation. When new_key is null
        // or equal to key, no rename happens and the validation is skipped.
        var op = request.op();
        boolean isRename = op == com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnOperation.RENAME;
        boolean isUpdateDisplayWithRename = op == com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnOperation.UPDATE_DISPLAY
                && request.newKey() != null && !request.newKey().isBlank()
                && !request.newKey().equals(request.key());

        if (isRename) {
            String newKey = request.newKey();
            if (newKey == null || newKey.trim().isEmpty()) {
                throw new IllegalArgumentException("new_key is required for rename operation.");
            }
        }
        if (isRename || isUpdateDisplayWithRename) {
            String newKey = request.newKey();
            String reservedErr = com.apimarketplace.datasource.tools.datasource.ToolParameterUtils
                .validateReservedColumnName(com.apimarketplace.datasource.tools.datasource.ToolParameterUtils
                    .sanitizeColumnName(newKey));
            if (reservedErr != null) {
                throw new IllegalArgumentException(reservedErr);
            }
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // 🇫🇷 Verification de l'existence de la DataSource
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        // 🇫🇷 Execution de l'operation sur les colonnes
        return repositories.manageColumn(dataSourceId, effectiveTenantId, request);
    }
    
    /**
     * Get preview of JSON keys for dynamic column detection
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param limit Maximum number of items to analyze
     * @return Map of JSON keys and their types
     */
    public Map<String, String> getJsonKeysPreview(Long dataSourceId, String tenantId, int limit) {
        return getJsonKeysPreview(dataSourceId, tenantId, null, null, limit);
    }

    public Map<String, String> getJsonKeysPreview(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            int limit) {
        String effectiveTenantId = resolveAccessibleTenantId(dataSourceId, tenantId, orgId, orgRole);
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null) {
            throw new IllegalArgumentException("dataSourceId and tenantId cannot be null");
        }
        
        // 🇫🇷 Verification de l'existence de la DataSource
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }
        
        // 🇫🇷 Recuperation des cles JSON pour detection dynamique des colonnes
        return repositories.getJsonKeysPreview(dataSourceId, effectiveTenantId, limit);
    }
    
    /**
     * Add a new item to a DataSource
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param data Data for the new item
     * @param priority Priority of the new item
     * @return Created item
     */
    public DataSourceItemRow addItem(Long dataSourceId, String tenantId, Map<String, Object> data, Integer priority) {
        return addItem(dataSourceId, tenantId, null, null, data, priority);
    }

    public DataSourceItemRow addItem(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            Map<String, Object> data,
            Integer priority) {
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null) {
            throw new IllegalArgumentException("dataSourceId and tenantId cannot be null");
        }

        if (data == null) {
            data = new java.util.HashMap<>();
        }
        if (priority == null) {
            priority = 1;
        }
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // 🇫🇷 Verification de l'existence de la DataSource
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        // 🇫🇷 Creation du nouvel item
        DataSourceItemRow created = repositories.addItem(dataSourceId, effectiveTenantId, data, priority);

        publishCreatedEvent(dataSourceId, effectiveTenantId, created);
        return created;
    }
    
    /**
     * Add a new column to a DataSource
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param columnName Name of the new column
     * @param columnType Type of the new column
     * @param defaultValue Default value for the new column
     * @return true if successful
     */
    public boolean addColumn(Long dataSourceId, String tenantId, String columnName, String columnType, String columnStructure, Map<String, Object> displayConfig, Object defaultValue) {
        return addColumn(dataSourceId, tenantId, null, null, columnName, columnType, columnStructure, displayConfig, defaultValue);
    }

    public boolean addColumn(
            Long dataSourceId,
            String tenantId,
            String orgId,
            String orgRole,
            String columnName,
            String columnType,
            String columnStructure,
            Map<String, Object> displayConfig,
            Object defaultValue) {
        // 🇫🇷 Validation des parametres
        if (dataSourceId == null || tenantId == null || columnName == null || columnName.trim().isEmpty()) {
            throw new IllegalArgumentException("dataSourceId, tenantId, and columnName cannot be null or empty");
        }

        // The OWNER of the table decides whether it may carry a vector column, so resolve it
        // before validating rather than after. Gating on the caller instead would refuse a FREE
        // member adding a column to their PRO workspace's table, and would let a PRO member add
        // one to a FREE workspace's table: a column that every subsequent write and search, all
        // owner-gated, would then refuse.
        String effectiveTenantId = resolveWritableTenantId(dataSourceId, tenantId, orgId, orgRole);

        // Type-level fail-fast: reserved names, valid type, plan gate (vector = a paid capability
        // on managed cloud), and display contract for select / multi_select / vector.
        // Same chokepoint as the agent tool path.
        String validationError = com.apimarketplace.datasource.tools.datasource.ToolParameterUtils
            .validateColumnDefinition(columnName, columnType, displayConfig,
                vectorFeatureGate.isVectorAllowed(effectiveTenantId),
                () -> vectorFeatureGate.deniedMessage(effectiveTenantId));
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        // 🇫🇷 Verification de l'existence de la DataSource
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new IllegalArgumentException("DataSource not found or access denied");
        }

        // 🇫🇷 Verification du nombre de colonnes (max 30)
        List<ColumnDefinition> existingColumns = repositories.getColumnDefinitions(dataSourceId, effectiveTenantId);
        // Count only user-defined columns (exclude system columns: id, priority, created_at)
        long userColumnCount = existingColumns.stream()
            .filter(col -> col.field().startsWith("data."))
            .count();
        if (userColumnCount >= MAX_COLUMNS_PER_DATASOURCE) {
            throw new IllegalArgumentException("Maximum number of columns reached (" + MAX_COLUMNS_PER_DATASOURCE + "). Please delete some columns before adding new ones.");
        }

        // 🇫🇷 Ajout de la colonne
        boolean added = repositories.addColumn(dataSourceId, effectiveTenantId, columnName, columnType, columnStructure, displayConfig, defaultValue);

        // HNSW index build for a new vector column - post-commit async event
        // (CREATE INDEX CONCURRENTLY self-deadlocks inside the open transaction).
        if (added && columnType != null && "vector".equalsIgnoreCase(columnType.trim())) {
            com.apimarketplace.datasource.events.VectorColumnCreatedEvent
                    .forColumn(dataSourceId, displayConfig)
                    .ifPresent(eventPublisher::publishEvent);
        }
        return added;
    }

    /**
     * Resolves the effective tenantId for queries that filter by {@code tenant_id} but are called
     * from an org context where the caller's tenant may differ from the datasource owner's
     * (i.e. teammate in the same org). When the caller's tenant owns the datasource → return as-is.
     * When the datasource belongs to the caller's org workspace and the caller has org access via
     * {@link DataSourceService#canAccessViaOrg} → swap to the owner's tenantId so downstream
     * tenant-filtered queries return rows. Falls back to the caller's tenantId in every other case.
     *
     * <p>Public so co-located services (e.g. {@link DataSourceExportService}) can apply the same
     * resolution before invoking tenant-filtered repository methods on
     * {@link com.apimarketplace.datasource.persistence.DataSourceItemQueryRepository}.
     */
    public String resolveAccessibleTenantId(Long dataSourceId, String callerTenantId, String orgId, String orgRole) {
        if (dataSourceId == null || callerTenantId == null) {
            return callerTenantId;
        }
        if (repositories.dataSourceExists(dataSourceId, callerTenantId)) {
            return callerTenantId;
        }
        if (orgId == null || orgId.isBlank()) {
            return callerTenantId;
        }

        return dataSourceService.getDataSource(dataSourceId)
            .filter(dataSource -> orgId.equals(dataSource.organizationId()))
            .filter(dataSource -> dataSourceService.canAccessViaOrg(
                orgId,
                callerTenantId,
                String.valueOf(dataSource.id()),
                orgRole))
            .map(DataSourceModels.DataSource::tenantId)
            .orElse(callerTenantId);
    }

    public String resolveWritableTenantId(Long dataSourceId, String callerTenantId, String orgId, String orgRole) {
        if (dataSourceId == null || callerTenantId == null) {
            return callerTenantId;
        }
        if (orgId != null && !orgId.isBlank()) {
            Optional<DataSourceModels.DataSource> dataSource = dataSourceService.getDataSource(dataSourceId)
                .filter(ds -> orgId.equals(ds.organizationId()));
            if (dataSource.isPresent()) {
                boolean writable = dataSourceService.canWriteViaOrg(
                    orgId,
                    callerTenantId,
                    String.valueOf(dataSource.get().id()),
                    orgRole);
                if (!writable) {
                    throw new IllegalArgumentException("DataSource not found or access denied");
                }
                return dataSource.get().tenantId();
            }
        }
        if (repositories.dataSourceExists(dataSourceId, callerTenantId)) {
            return callerTenantId;
        }
        return callerTenantId;
    }

    // ==================== Event emission helpers ====================

    /**
     * Flatten a DataSourceItemRow to the same shape CRUD events use: user columns
     * at the top level plus the system columns (id, created_at). Keeps the payload
     * identical regardless of which service path mutated the row.
     */
    private Map<String, Object> flattenItemRow(DataSourceItemRow row) {
        Map<String, Object> flat = new LinkedHashMap<>();
        if (row.data() != null) flat.putAll(row.data());
        flat.put("id", row.id());
        flat.put("priority", row.priority());
        if (row.createdAt() != null) flat.put("created_at", row.createdAt().toString());
        return flat;
    }

    /**
     * The same row, with its media cells inflated - the shape a workflow gets when it READS the
     * table. Rows leaving this service as trigger events must match, or the shape of
     * {@code trigger:<label>.output.row.<media column>} would depend on WHICH route wrote the row
     * (this service for a grid edit, the CRUD service for an agent or workflow write) rather than
     * on the column's declared type.
     */
    private Map<String, Object> flattenItemRowForEvent(DataSourceModels.DataSource dataSource,
                                                       DataSourceItemRow row) {
        return mediaCellHydrator.hydrateRow(flattenItemRow(row),
                dataSource != null ? dataSource.mappingSpec() : null);
    }

    /**
     * The row's stored classification (CASA LC-066), carried with its row events: a table trigger
     * fired by editing or deleting a Gmail-derived row in the table UI must restrict the run it
     * feeds, as the same edit made by a tool does. A failed read leaves the rows untagged (read as
     * NORMAL) rather than failing the user's edit.
     */
    private Map<Long, String> sensitivitiesOf(Long dataSourceId, String tenantId, List<Long> itemIds) {
        try {
            Map<Long, String> byId = repositories.sensitivityByIds(dataSourceId, tenantId, itemIds);
            return byId != null ? byId : Map.of();
        } catch (Exception e) {
            log.warn("Could not read the sensitivity of datasource={} rows={}: {}", dataSourceId, itemIds, e.getMessage());
            return Map.of();
        }
    }

    private static String orgIdOf(DataSourceModels.DataSource dataSource) {
        return dataSource != null ? dataSource.organizationId() : null;
    }

    private Map<String, Object> findFlattenedById(Long dataSourceId, String tenantId, Long itemId) {
        List<DataSourceItemRow> rows = repositories.findByIds(dataSourceId, tenantId, List.of(itemId));
        if (rows == null || rows.isEmpty()) return null;
        return flattenItemRowForEvent(dataSourceForEvent(dataSourceId), rows.get(0));
    }

    private Map<Long, Map<String, Object>> snapshotFlattenedByIds(Long dataSourceId, String tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        // Resolved once for the batch: these rows all belong to the same table.
        DataSourceModels.DataSource dataSource = dataSourceForEvent(dataSourceId);
        for (DataSourceItemRow row : repositories.findByIds(dataSourceId, tenantId, ids)) {
            out.put(row.id(), flattenItemRowForEvent(dataSource, row));
        }
        return out;
    }

    /**
     * The datasource behind an event emission, resolved ONCE for both things the emission needs:
     * its workspace org (the downstream listener carries it through the @Async / AFTER_COMMIT
     * boundary so {@code DatasourceTriggerDispatchService} can refuse cross-workspace fan-out) and
     * its column spec (so media cells go out in the shape a workflow reading the same row sees).
     * One cheap getDataSource per write - resolving them separately cost two.
     */
    private DataSourceModels.DataSource dataSourceForEvent(Long dataSourceId) {
        try {
            return dataSourceService.getDataSource(dataSourceId).orElse(null);
        } catch (Exception e) {
            // Logged, not swallowed in silence: without it the event still goes out, but its media
            // cells stay in the stored text form - the one shape a workflow reading the same row
            // will NOT see - and the workspace scope is unset. A run of these lines explains an
            // otherwise baffling "sometimes it is a string" report.
            log.warn("Could not read datasource={} while emitting a row event - its media cells "
                    + "keep their stored text form and the workspace scope is unset: {}",
                    dataSourceId, e.getMessage());
            return null;
        }
    }

    private void publishCreatedEvent(Long dataSourceId, String tenantId, DataSourceItemRow created) {
        if (rowEventPublisher == null || created == null) return;
        try {
            DataSourceModels.DataSource dataSource = dataSourceForEvent(dataSourceId);
            rowEventPublisher.publishCreated(dataSourceId, created.id(), tenantId,
                    orgIdOf(dataSource), flattenItemRowForEvent(dataSource, created));
        } catch (Exception e) {
            log.warn("Failed to publish row_created for datasource={} row={}: {}",
                    dataSourceId, created.id(), e.getMessage());
        }
    }

    private void publishUpdatedEvent(Long dataSourceId, String tenantId,
                                     DataSourceItemRow updated, Map<String, Object> before,
                                     String sensitivity) {
        if (rowEventPublisher == null || updated == null) return;
        try {
            DataSourceModels.DataSource dataSource = dataSourceForEvent(dataSourceId);
            rowEventPublisher.publishUpdated(dataSourceId, updated.id(), tenantId,
                    orgIdOf(dataSource), flattenItemRowForEvent(dataSource, updated), before, sensitivity);
        } catch (Exception e) {
            log.warn("Failed to publish row_updated for datasource={} row={}: {}",
                    dataSourceId, updated.id(), e.getMessage());
        }
    }

    private void publishBulkEvents(Long dataSourceId, String tenantId,
                                   BulkOperationRequest request,
                                   Map<Long, Map<String, Object>> beforeSnapshots,
                                   Map<Long, String> sensitivities) {
        if (rowEventPublisher == null) return;
        // Resolve org once for the whole bulk batch to avoid one lookup per row.
        String orgId = orgIdOf(dataSourceForEvent(dataSourceId));
        BulkOperationType op = request.op();
        if (op == BulkOperationType.DELETE) {
            for (Long id : request.ids()) {
                Map<String, Object> row = beforeSnapshots.get(id);
                if (row == null) continue; // id wasn't visible pre-op - skip
                try {
                    rowEventPublisher.publishDeleted(dataSourceId, id, tenantId, orgId, row, sensitivities.get(id));
                } catch (Exception e) {
                    log.warn("Failed to publish row_deleted for datasource={} row={}: {}",
                            dataSourceId, id, e.getMessage());
                }
            }
            return;
        }
        // Patch / update-style bulk op - re-read post-op state for each id.
        Map<Long, Map<String, Object>> afterSnapshots = snapshotFlattenedByIds(dataSourceId, tenantId, request.ids());
        for (Long id : request.ids()) {
            Map<String, Object> after = afterSnapshots.get(id);
            if (after == null) continue;
            Map<String, Object> before = beforeSnapshots.get(id);
            try {
                rowEventPublisher.publishUpdated(dataSourceId, id, tenantId, orgId, after, before, sensitivities.get(id));
            } catch (Exception e) {
                log.warn("Failed to publish row_updated for datasource={} row={}: {}",
                        dataSourceId, id, e.getMessage());
            }
        }
    }
}
