package com.apimarketplace.datasource.controllers.datasource;

/**
 * Unified REST controller for DataSource functionality.
 *
 * <h2>Architecture</h2>
 * This package provides DataSource management through specialized controllers:
 *
 * <h3>Controllers</h3>
 * <ul>
 *   <li>{@link DataSourceCrudController} - Basic CRUD operations (create, read, update, delete)</li>
 *   <li>{@link DataSourceItemController} - Item operations (pagination, CRUD, bulk)</li>
 *   <li>{@link DataSourceColumnController} - Column management (definitions, add, rename, order)</li>
 *   <li>{@link DataSourceExportController} - Export operations (CSV, JSON, XLSX)</li>
 *   <li>{@link DataSourceNestedController} - Nested JSON navigation</li>
 * </ul>
 *
 * <h3>Supporting Components</h3>
 * <ul>
 *   <li>{@link TenantIdResolver} - Resolves tenantId from X-User-ID header or query param</li>
 *   <li>{@link DataSourceRequestParser} - Parses sort, filter parameters and JSON patches</li>
 * </ul>
 *
 * <h3>Base Path</h3>
 * All endpoints are mounted at: <code>/api/data-sources</code>
 *
 * <h3>Authentication</h3>
 * CORS is handled by the Gateway. Tenant identification uses X-User-ID header
 * (injected by Gateway from JWT) with fallback to tenantId query parameter.
 *
 * @see DataSourceCrudController
 * @see DataSourceItemController
 * @see DataSourceColumnController
 * @see DataSourceExportController
 * @see DataSourceNestedController
 */
public final class DataSourceController {
    // This class is documentation only.
    // All endpoints are provided by the specialized controllers in this package.
    private DataSourceController() {
        // Prevent instantiation
    }
}
