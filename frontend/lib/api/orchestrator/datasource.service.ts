/**
 * DataSource Service
 *
 * Handles datasource CRUD, columns, items, and export operations.
 * Single Responsibility: Only datasource-related operations.
 */

import { apiClient, ApiError } from '../api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import type { DataSource, DataSourceColumn, DataSourceItem, PaginatedResponse } from './types';

export class DataSourceService {
  // ========================================
  // Data Sources CRUD
  // ========================================

  /**
   * Get all data sources for current user
   */
  async getDataSources(): Promise<DataSource[]> {
    return apiClient.get<DataSource[]>('/data-sources');
  }

  /**
   * Paged, DB-searchable, server-sorted + server-visibility-filtered list of data sources.
   * `q` is matched server-side against name + description. `sort` (name | lastModified) and
   * `visibility` (all | public | private) are applied server-side over the whole set BEFORE
   * paginating, so the page is final - the caller renders `items` as-is and never loads more than
   * it shows. `publicationStatuses` carries each shared row's badge (id -> {status, rejectionReason?},
   * absent = not shared), batched server-side to replace the former per-row status fan-out.
   */
  async getDataSourcesPage(options: {
    page?: number;
    size?: number;
    q?: string;
    // Server tolerates any value (unknown keys default to lastModified), so this stays a plain
    // string to accept the caller's wider ListSortKey without coupling this client to that type.
    sort?: string;
    visibility?: 'all' | 'public' | 'private';
  } = {}): Promise<{
    items: DataSource[];
    totalCount: number;
    page: number;
    size: number;
    rowCounts: Record<string, number>;
    sampleRows: Record<string, Array<Record<string, unknown>>>;
    publicationStatuses: Record<string, { status: string; rejectionReason?: string }>;
  }> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    if (options.sort) params.sort = options.sort;
    if (options.visibility) params.visibility = options.visibility;
    const data = await apiClient.get<any>('/data-sources/paged', { params });
    return {
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 25,
      // Per-datasource row counts for the page (id -> count), batched server-side.
      rowCounts: data.rowCounts ?? {},
      // Per-datasource first-N row sample (id -> [row data…]), batched server-side,
      // for each card's mini-table preview.
      sampleRows: data.sampleRows ?? {},
      // Per-datasource publication badge for the page (id -> {status, rejectionReason?}),
      // batched server-side - replaces the per-row is-resource-published sweep.
      publicationStatuses: data.publicationStatuses ?? {},
    };
  }

  /**
   * Get a single data source by ID
   */
  async getDataSource(id: string): Promise<DataSource> {
    return apiClient.get<DataSource>(`/data-sources/${id}`);
  }

  /**
   * Create a new data source
   */
  async createDataSource(dataSource: Partial<DataSource>): Promise<DataSource> {
    return apiClient.post<DataSource>('/data-sources', dataSource);
  }

  /**
   * Update a data source
   */
  async updateDataSource(id: string, dataSource: Partial<DataSource>): Promise<DataSource> {
    return apiClient.put<DataSource>(`/data-sources/${id}`, dataSource);
  }

  /**
   * Delete a data source
   */
  async deleteDataSource(id: string): Promise<void> {
    return apiClient.delete<void>(`/data-sources/${id}`);
  }

  /**
   * Clone a data source
   */
  async cloneDataSource(id: string): Promise<DataSource> {
    return apiClient.post<DataSource>(`/data-sources/${id}/clone`, {});
  }

  /**
   * Create demo data source
   */
  async createDemoDataSource(): Promise<DataSource> {
    return apiClient.post<DataSource>('/data-sources/demo', null);
  }

  // ========================================
  // Columns
  // ========================================

  /**
   * Get columns for a data source
   */
  async getColumns(dataSourceId: string): Promise<DataSourceColumn[]> {
    const numericId = parseInt(dataSourceId, 10);
    if (!dataSourceId || isNaN(numericId) || numericId <= 0) {
      return [];
    }
    return apiClient.get<DataSourceColumn[]>(`/data-sources/${dataSourceId}/columns`);
  }

  /**
   * Get nested columns (for JSON path)
   */
  async getNestedColumns(dataSourceId: string, jsonPath: string): Promise<DataSourceColumn[]> {
    return apiClient.get<DataSourceColumn[]>(`/data-sources/${dataSourceId}/columns/nested`, {
      params: { path: jsonPath }
    });
  }

  /**
   * Create a column
   */
  async createColumn(dataSourceId: string, column: Partial<DataSourceColumn>): Promise<DataSourceColumn> {
    return apiClient.post<DataSourceColumn>(`/data-sources/${dataSourceId}/columns`, column);
  }

  /**
   * Update column
   */
  async updateColumn(
    dataSourceId: string,
    columnId: string,
    column: Partial<DataSourceColumn>
  ): Promise<DataSourceColumn> {
    return apiClient.put<DataSourceColumn>(`/data-sources/${dataSourceId}/columns/${columnId}`, column);
  }

  /**
   * Delete column
   */
  async deleteColumn(dataSourceId: string, columnId: string): Promise<void> {
    return apiClient.delete<void>(`/data-sources/${dataSourceId}/columns/${columnId}`);
  }

  /**
   * Bulk update columns
   */
  async bulkUpdateColumns(dataSourceId: string, columns: any): Promise<void> {
    return apiClient.put<void>(`/data-sources/${dataSourceId}/columns/bulk`, columns);
  }

  /**
   * Update column order
   */
  async updateColumnOrder(dataSourceId: string, order: string[]): Promise<void> {
    return apiClient.put<void>(`/data-sources/${dataSourceId}/column-order`, order);
  }

  // ========================================
  // Items
  // ========================================

  /**
   * Get items from a data source (paginated)
   */
  async getItems(
    dataSourceId: string,
    options: { page?: number; limit?: number; jsonPath?: string } = {}
  ): Promise<PaginatedResponse<DataSourceItem>> {
    const { page = 0, limit = 50, jsonPath } = options;
    const path = jsonPath
      ? `/data-sources/${dataSourceId}/items/nested`
      : `/data-sources/${dataSourceId}/items`;

    return apiClient.get<PaginatedResponse<DataSourceItem>>(path, {
      params: {
        page,
        limit,
        ...(jsonPath && { path: jsonPath })
      }
    });
  }

  /**
   * Create an item
   */
  async createItem(dataSourceId: string, item: any): Promise<DataSourceItem> {
    return apiClient.post<DataSourceItem>(`/data-sources/${dataSourceId}/items`, item);
  }

  /**
   * Update an item
   */
  async updateItem(dataSourceId: string, itemId: string, item: any): Promise<DataSourceItem> {
    return apiClient.put<DataSourceItem>(`/data-sources/${dataSourceId}/items/${itemId}`, item);
  }

  /**
   * Delete an item
   */
  async deleteItem(dataSourceId: string, itemId: string): Promise<void> {
    return apiClient.delete<void>(`/data-sources/${dataSourceId}/items/${itemId}`);
  }

  /**
   * Bulk update items
   */
  async bulkUpdateItems(dataSourceId: string, updates: any): Promise<void> {
    return apiClient.put<void>(`/data-sources/${dataSourceId}/bulk`, updates);
  }

  // ========================================
  // Nested Items
  // ========================================

  /**
   * Get nested items from a data source
   */
  async getNestedItems(
    dataSourceId: string,
    jsonPath: string,
    options: { page?: number; limit?: number } = {}
  ): Promise<PaginatedResponse<DataSourceItem>> {
    const { page = 0, limit = 50 } = options;
    return apiClient.get<PaginatedResponse<DataSourceItem>>(`/data-sources/${dataSourceId}/items/nested`, {
      params: { path: jsonPath, page, limit }
    });
  }

  /**
   * Update nested item
   */
  async updateNestedItem(
    dataSourceId: string,
    itemId: string,
    jsonPath: string,
    item: any
  ): Promise<DataSourceItem> {
    return apiClient.put<DataSourceItem>(`/data-sources/${dataSourceId}/items/${itemId}/nested`, item, {
      params: { path: jsonPath }
    });
  }

  /**
   * Delete nested item
   */
  async deleteNestedItem(
    dataSourceId: string,
    itemId: string,
    jsonPath: string
  ): Promise<void> {
    return apiClient.delete<void>(`/data-sources/${dataSourceId}/items/${itemId}/nested`, {
      params: { path: jsonPath }
    });
  }

  /**
   * Add nested item
   */
  async addNestedItem(
    dataSourceId: string,
    jsonPath: string,
    item: any
  ): Promise<DataSourceItem> {
    return apiClient.post<DataSourceItem>(`/data-sources/${dataSourceId}/items/nested`, item, {
      params: { path: jsonPath }
    });
  }

  // ========================================
  // Tables & Export
  // ========================================

  /**
   * Get tables for a database data source
   */
  async getTables(dataSourceId: string): Promise<string[]> {
    const numericId = parseInt(dataSourceId, 10);
    if (!dataSourceId || isNaN(numericId) || numericId <= 0) {
      return [];
    }
    return apiClient.get<string[]>(`/data-sources/${dataSourceId}/tables`);
  }

  /**
   * Export data source to file
   */
  async exportDataSource(
    dataSourceId: string,
    options: { format?: 'xlsx' | 'csv'; columns?: string[] } = {}
  ): Promise<Blob> {
    const { format = 'xlsx', columns } = options;
    const params = new URLSearchParams({
      format,
      ...(columns && { columns: columns.join(',') })
    });

    const url = `/api/proxy/data-sources/${dataSourceId}/export?${params}`;
    const token = await apiClient.getTokenProvider()?.();

    const response = await fetch(url, {
      // Include the active-workspace header like every other raw-fetch site, otherwise the
      // gateway resolves the export under the user's DEFAULT org and a datasource opened from
      // a non-default workspace 404s (or exports the wrong workspace's data).
      headers: {
        ...getActiveOrgHeaderForRequest(),
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      credentials: 'include'
    });

    if (!response.ok) {
      throw new ApiError(`Export failed: ${response.status}`, response.status);
    }

    return response.blob();
  }
}

export const dataSourceService = new DataSourceService();
