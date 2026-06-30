package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Facade repository for enhanced DataSource operations with JSONB support.
 * Delegates to specialized repositories for specific concerns.
 *
 * Architecture: Controller → Service → Repository
 * Following SOLID principles and clean architecture.
 */
@Repository
public class DataSourceEnhancedRepositories {

    private final DataSourceItemQueryRepository queryRepository;
    private final DataSourceColumnRepository columnRepository;
    private final DataSourceBulkOperationRepository bulkOperationRepository;

    public DataSourceEnhancedRepositories(
            DataSourceItemQueryRepository queryRepository,
            DataSourceColumnRepository columnRepository,
            DataSourceBulkOperationRepository bulkOperationRepository) {
        this.queryRepository = queryRepository;
        this.columnRepository = columnRepository;
        this.bulkOperationRepository = bulkOperationRepository;
    }

    // ========== Query Operations (delegated to DataSourceItemQueryRepository) ==========

    /**
     * Check if a DataSource exists and belongs to the tenant.
     */
    public boolean dataSourceExists(Long dataSourceId, String tenantId) {
        return queryRepository.dataSourceExists(dataSourceId, tenantId);
    }

    /**
     * Check if an item exists and belongs to the tenant and data source.
     */
    public boolean itemExists(Long dataSourceId, String tenantId, Long itemId) {
        return queryRepository.itemExists(dataSourceId, tenantId, itemId);
    }

    /**
     * Find items with server-side pagination, filtering, and sorting.
     */
    public List<DataSourceItemRow> findItemsWithPagination(
            Long dataSourceId,
            String tenantId,
            PaginationRequest request) {
        return queryRepository.findItemsWithPagination(dataSourceId, tenantId, request);
    }

    /**
     * Find items with offset-based pagination.
     */
    public List<DataSourceItemRow> findItemsWithOffset(
            Long dataSourceId,
            String tenantId,
            PaginationRequest request,
            int offset) {
        return queryRepository.findItemsWithOffset(dataSourceId, tenantId, request, offset);
    }

    /**
     * Count total items for a data source with filters.
     */
    public int countItemsWithFilters(Long dataSourceId, String tenantId, PaginationRequest request) {
        return queryRepository.countItemsWithFilters(dataSourceId, tenantId, request);
    }

    /**
     * Find items by their IDs.
     */
    public List<DataSourceItemRow> findByIds(Long dataSourceId, String tenantId, List<Long> ids) {
        return queryRepository.findByIds(dataSourceId, tenantId, ids);
    }

    // ========== Column Operations (delegated to DataSourceColumnRepository) ==========

    /**
     * Get column definitions for dynamic table columns.
     */
    public List<ColumnDefinition> getColumnDefinitions(Long dataSourceId, String tenantId) {
        return columnRepository.getColumnDefinitions(dataSourceId, tenantId);
    }

    /**
     * Save column order for a DataSource.
     */
    public boolean saveColumnOrder(Long dataSourceId, String tenantId, List<Map<String, Object>> columnOrder) {
        return columnRepository.saveColumnOrder(dataSourceId, tenantId, columnOrder);
    }

    /**
     * Add a new column to a DataSource.
     */
    public boolean addColumn(Long dataSourceId, String tenantId, String columnName, String columnType,
                             String columnStructure, Map<String, Object> displayConfig, Object defaultValue) {
        return columnRepository.addColumn(dataSourceId, tenantId, columnName, columnType,
            columnStructure, displayConfig, defaultValue);
    }

    /**
     * Manage columns (drop, rename, set default value).
     */
    public ColumnManagementResult manageColumn(Long dataSourceId, String tenantId, ColumnManagementRequest request) {
        return columnRepository.manageColumn(dataSourceId, tenantId, request);
    }

    /**
     * Get preview of JSON keys for dynamic column detection.
     */
    public Map<String, String> getJsonKeysPreview(Long dataSourceId, String tenantId, int limit) {
        return columnRepository.getJsonKeysPreview(dataSourceId, tenantId);
    }

    // ========== Bulk Operations (delegated to DataSourceBulkOperationRepository) ==========

    /**
     * Add a new item to a DataSource.
     */
    public DataSourceItemRow addItem(Long dataSourceId, String tenantId, Map<String, Object> data, Integer priority) {
        return bulkOperationRepository.addItem(dataSourceId, tenantId, data, priority);
    }

    /**
     * Delete a single item.
     */
    public void deleteItem(Long dataSourceId, String tenantId, Long itemId) {
        bulkOperationRepository.deleteItem(dataSourceId, tenantId, itemId);
    }

    /**
     * Apply JSON patch operations to a single item.
     */
    public DataSourceItemRow applyJsonPatch(
            Long dataSourceId,
            String tenantId,
            Long itemId,
            List<JsonPatchOperation> patches) {
        return bulkOperationRepository.applyJsonPatch(dataSourceId, tenantId, itemId, patches);
    }

    /**
     * Execute bulk operations (delete or patch multiple items).
     */
    public BulkOperationResult executeBulkOperation(
            Long dataSourceId,
            String tenantId,
            BulkOperationRequest request) {
        return bulkOperationRepository.executeBulkOperation(dataSourceId, tenantId, request);
    }
}
