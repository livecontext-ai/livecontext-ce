'use client';

import { useCallback, useState } from 'react';
import type { ColumnDefinition, DataSourceItemRow, PaginationState } from '../types';
import { apiClient } from '@/lib/api';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

// Helper function to make authenticated fetch calls
// Uses apiClient's token provider to get the auth token
async function authenticatedFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const tokenProvider = apiClient.getTokenProvider();
  let token: string | null = null;

  if (tokenProvider) {
    try {
      token = await tokenProvider();
    } catch (e) {
      console.warn('[authenticatedFetch] Failed to get token:', e);
    }
  }

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  if (token) {
    (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
  }

  return fetch(url, {
    ...options,
    headers,
  });
}

export interface UseTableExportParams {
  dataSourceId?: number;
  rows: DataSourceItemRow[];
  columns: ColumnDefinition[];
  selectedRows: Set<string>;
  selectedColumns: Set<string>;
  getRowUniqueKey: (row: DataSourceItemRow) => string;
  searchQuery: string;
  sortConfig: { key: string; direction: 'asc' | 'desc' } | null;
  pagination: PaginationState;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
  /** Optional function to get unique columns. If not provided, columns will be deduplicated internally. */
  getUniqueColumns?: () => ColumnDefinition[];
}

export interface UseTableExportReturn {
  // State
  exportLoading: boolean;
  selectedExportFormat: string | null;
  // Setters
  setSelectedExportFormat: React.Dispatch<React.SetStateAction<string | null>>;
  // Functions
  getDynamicColumns: () => ColumnDefinition[];
  convertToCSV: (rowsToExport: DataSourceItemRow[], columnsToUse: ColumnDefinition[]) => string;
  downloadFile: (content: string, filename: string, mimeType: string) => void;
  handleExportCSV: () => Promise<void>;
  handleExportJSON: () => Promise<void>;
  handleExportExcel: () => Promise<void>;
  handleExportFull: (format: 'csv' | 'json' | 'xlsx') => Promise<void>;
}

/**
 * Hook for managing data table export functionality.
 * Supports CSV, JSON, and Excel export formats.
 */
export function useTableExport({
  dataSourceId,
  rows,
  columns,
  selectedRows,
  selectedColumns,
  getRowUniqueKey,
  searchQuery,
  sortConfig,
  pagination,
  addToast,
  getUniqueColumns: getUniqueColumnsExternal,
}: UseTableExportParams): UseTableExportReturn {
  const [exportLoading, setExportLoading] = useState(false);
  const [selectedExportFormat, setSelectedExportFormat] = useState<string | null>(null);

  // Internal function to deduplicate columns
  const getUniqueColumnsInternal = useCallback(() => {
    return columns.filter((col, idx, arr) =>
      arr.findIndex(c => c.field === col.field) === idx
    );
  }, [columns]);

  // Use external getUniqueColumns if provided, otherwise use internal deduplication
  const getUniqueColumns = getUniqueColumnsExternal || getUniqueColumnsInternal;

  // Get dynamic columns for forms (excludes fixed columns)
  const getDynamicColumns = useCallback(() => {
    return getUniqueColumns().filter(col =>
      !['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'].includes(col.field)
    );
  }, [getUniqueColumns]);

  // Convert data to CSV format
  const convertToCSV = useCallback((rowsToExport: DataSourceItemRow[], columnsToUse: ColumnDefinition[]) => {
    // Headers
    const baseHeaders = ['ID', 'Priority', 'Created At'];
    const dataHeaders = columnsToUse
      .filter(col => !['checkbox', 'id', 'priority', 'created_at'].includes(col.field))
      .map(col => col.header_name);
    const headers = [...baseHeaders, ...dataHeaders];

    // Data rows (excluding tenant_id and data_source_id for security)
    const dataRows = rowsToExport.map(row => {
      const baseValues = [
        String(row.id),
        String(row.priority ?? ''),
        row.created_at ? formatUtcDateTime(row.created_at) : ''
      ];

      const dataValues = columnsToUse
        .filter(col => !['checkbox', 'id', 'priority', 'created_at'].includes(col.field))
        .map(col => {
          const field = col.field.replace('data.', '');
          const value = row.data?.[field];

          if (value === null || value === undefined) {
            return '';
          }

          if (typeof value === 'object') {
            return JSON.stringify(value);
          }

          // Escape quotes and commas for CSV
          const stringValue = String(value);
          if (stringValue.includes(',') || stringValue.includes('"') || stringValue.includes('\n')) {
            return `"${stringValue.replace(/"/g, '""')}"`;
          }

          return stringValue;
        });

      return [...baseValues, ...dataValues];
    });

    // Combine all
    const csvContent = [
      headers.join(','),
      ...dataRows.map(row => row.join(','))
    ].join('\n');

    return csvContent;
  }, []);

  // Download a file
  const downloadFile = useCallback((content: string, filename: string, mimeType: string) => {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }, []);

  // Export to CSV
  const handleExportCSV = useCallback(async () => {
    if (!dataSourceId) return;

    setExportLoading(true);
    try {
      // Small delay to allow spinner to display
      await new Promise(resolve => setTimeout(resolve, 100));

      const rowsToExport = selectedRows.size > 0
        ? rows.filter(row => selectedRows.has(getRowUniqueKey(row)))
        : rows;

      // Use visible columns if some columns are selected
      let columnsToUse = columns;
      if (selectedColumns.size > 0 && selectedColumns.size < columns.length) {
        columnsToUse = columns.filter(col =>
          selectedColumns.has(col.field) || ['id', 'priority', 'created_at', 'array_index', 'value'].includes(col.field)
        );
      }

      const csvContent = convertToCSV(rowsToExport, columnsToUse);
      const filename = `datasource_${dataSourceId}_export_${new Date().toISOString().split('T')[0]}.csv`;
      downloadFile(csvContent, filename, 'text/csv');

      // Reset after successful export
      setSelectedExportFormat(null);
    } catch (err) {
      console.error('Error exporting CSV:', err);
      addToast({
        type: 'error',
        title: 'Export Error',
        message: 'Failed to export CSV'
      });
    } finally {
      setExportLoading(false);
    }
  }, [dataSourceId, rows, columns, selectedRows, selectedColumns, getRowUniqueKey, convertToCSV, downloadFile, addToast]);

  // Export to JSON
  const handleExportJSON = useCallback(async () => {
    if (!dataSourceId) return;

    setExportLoading(true);
    try {
      // Small delay to allow spinner to display
      await new Promise(resolve => setTimeout(resolve, 100));

      const rowsToExport = selectedRows.size > 0
        ? rows.filter(row => selectedRows.has(getRowUniqueKey(row)))
        : rows;

      // Use visible columns if some columns are selected
      let columnsToUse = columns;
      if (selectedColumns.size > 0 && selectedColumns.size < columns.length) {
        columnsToUse = columns.filter(col =>
          selectedColumns.has(col.field) || ['id', 'priority', 'created_at', 'array_index', 'value'].includes(col.field)
        );
      }

      const jsonData = {
        exportDate: new Date().toISOString(),
        dataSourceId: dataSourceId,
        totalRows: rowsToExport.length,
        exportedRows: selectedRows.size > 0 ? Array.from(selectedRows) : 'all',
        columns: columnsToUse.map(col => ({
          id: col.col_id,
          name: col.header_name,
          field: col.field,
          type: col.type
        })),
        data: rowsToExport.map(row => {
          const rowData: Record<string, unknown> = {
            id: row.id,
            priority: row.priority,
            created_at: row.created_at
          };

          columnsToUse
            .filter(col => !['checkbox', 'id', 'priority', 'created_at'].includes(col.field))
            .forEach(col => {
              const field = col.field.replace('data.', '');
              rowData[field] = row.data?.[field];
            });

          return rowData;
        })
      };

      const jsonContent = JSON.stringify(jsonData, null, 2);
      const filename = `datasource_${dataSourceId}_export_${new Date().toISOString().split('T')[0]}.json`;
      downloadFile(jsonContent, filename, 'application/json');

      // Reset after successful export
      setSelectedExportFormat(null);
    } catch (err) {
      console.error('Error exporting JSON:', err);
      addToast({
        type: 'error',
        title: 'Export Error',
        message: 'Failed to export JSON'
      });
    } finally {
      setExportLoading(false);
    }
  }, [dataSourceId, rows, columns, selectedRows, selectedColumns, getRowUniqueKey, downloadFile, addToast]);

  // Export to Excel (backend call)
  const handleExportExcel = useCallback(async () => {
    if (!dataSourceId) return;

    setExportLoading(true);
    try {
      const params = new URLSearchParams();

      if (selectedRows.size > 0) {
        params.set('ids', Array.from(selectedRows).join(','));
      }

      if (selectedColumns.size > 0 && selectedColumns.size < columns.length) {
        const visibleColumns = Array.from(selectedColumns)
          .filter(field => !['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'].includes(field))
          .map(field => field.replace('data.', ''));
        if (visibleColumns.length > 0) {
          params.set('columns', visibleColumns.join(','));
        }
      }

      if (searchQuery) {
        params.set('search', searchQuery);
      }

      if (sortConfig) {
        params.set('sort', `${sortConfig.key}:${sortConfig.direction}`);
      }

      params.set('limit', String(pagination.pageSize));
      if (pagination.nextCursor) {
        params.set('cursor', pagination.nextCursor);
      }

      const response = await authenticatedFetch(
        `/api/proxy/data-sources/${dataSourceId}/export?format=xlsx&${params.toString()}`,
        {
          method: 'GET',
          headers: {
            'Accept': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          },
        }
      );

      if (!response.ok) {
        const errorText = await response.text().catch(() => '');
        throw new Error(`Backend returned ${response.status}: ${errorText || response.statusText}`);
      }

      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `datasource_${dataSourceId}_export_${new Date().toISOString().split('T')[0]}.xlsx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);

      // Reset after successful export
      setSelectedExportFormat(null);
    } catch (err) {
      console.error('Error exporting Excel:', err);
      const errorMessage = err instanceof Error && err.message.includes('Failed to fetch')
        ? 'Backend endpoint not available. Please ensure the backend server is running and the export endpoint is implemented.'
        : `Failed to export Excel: ${err instanceof Error ? err.message : 'Unknown error'}`;

      addToast({
        type: 'error',
        title: 'Export Error',
        message: errorMessage
      });
    } finally {
      setExportLoading(false);
    }
  }, [dataSourceId, columns, selectedRows, selectedColumns, searchQuery, sortConfig, pagination, addToast]);

  // Full export (backend call with format parameter)
  const handleExportFull = useCallback(async (format: 'csv' | 'json' | 'xlsx') => {
    if (!dataSourceId) return;

    setExportLoading(true);
    try {
      const params = new URLSearchParams();

      params.set('format', format);

      if (selectedRows.size > 0) {
        params.set('ids', Array.from(selectedRows).join(','));
      }

      if (selectedColumns.size > 0 && selectedColumns.size < columns.length) {
        const visibleColumns = Array.from(selectedColumns)
          .filter(field => !['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'].includes(field))
          .map(field => field.replace('data.', ''));
        if (visibleColumns.length > 0) {
          params.set('columns', visibleColumns.join(','));
        }
      }

      const mimeTypes: Record<string, string> = {
        csv: 'text/csv',
        json: 'application/json',
        xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      };

      const response = await authenticatedFetch(
        `/api/proxy/data-sources/${dataSourceId}/export?${params.toString()}`,
        {
          method: 'GET',
          headers: {
            'Accept': mimeTypes[format],
          },
        }
      );

      if (!response.ok) {
        const errorText = await response.text().catch(() => '');
        throw new Error(`Backend returned ${response.status}: ${errorText || response.statusText}`);
      }

      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `datasource_${dataSourceId}_export_${new Date().toISOString().split('T')[0]}.${format}`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);

      // Reset after successful export
      setSelectedExportFormat(null);
    } catch (err) {
      console.error(`Error exporting ${format}:`, err);
      const errorMessage = err instanceof Error && err.message.includes('Failed to fetch')
        ? `Backend endpoint not available. Please ensure the backend server is running and the export endpoint is implemented.`
        : `Failed to export ${format.toUpperCase()}: ${err instanceof Error ? err.message : 'Unknown error'}`;

      addToast({
        type: 'error',
        title: 'Export Error',
        message: errorMessage
      });
    } finally {
      setExportLoading(false);
    }
  }, [dataSourceId, columns, selectedRows, selectedColumns, addToast]);

  return {
    // State
    exportLoading,
    selectedExportFormat,
    // Setters
    setSelectedExportFormat,
    // Functions
    getDynamicColumns,
    convertToCSV,
    downloadFile,
    handleExportCSV,
    handleExportJSON,
    handleExportExcel,
    handleExportFull,
  };
}
