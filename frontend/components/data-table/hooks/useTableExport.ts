'use client';

import { useCallback, useState } from 'react';
import type { ColumnDefinition, DataSourceItemRow, PaginationState } from '../types';
import { displayZoneFor, formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { authenticatedFetch } from '../utils/authenticatedFetch';
import { cellDisplayText } from '@/lib/datatable/assetValue';
import { displayIdOf } from '../utils/dataTableUtils';

/** Quote a CSV field when it carries a separator, a quote, or a newline. */
function escapeCsv(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n')) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
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
  /**
   * Fields the view renders as FIXED columns (see `getFixedColumns(viewConfig)`). They are the ones
   * excluded from `getDynamicColumns`, which drives the add-row form and the column-picker modal.
   * Required: there is no safe default - the legacy reserved-name list is wrong during nested
   * navigation, where it would drop the item's own fields from the export.
   */
  fixedColumnFields: string[];
  /**
   * Fields the EXPORT writes out of the row rather than out of `row.data`, i.e. the ones its base
   * columns already carry (see `getRowLevelExportFields(viewConfig)`). Distinct from
   * `fixedColumnFields`: `value` / `array_index` are lanes in the grid but have no base column, so
   * they must still reach the file as data.
   */
  rowLevelFields: string[];
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
  fixedColumnFields,
  rowLevelFields,
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

  // Get dynamic columns for forms (excludes the columns the view renders itself).
  // Driven by the CALLER's fixed set, not a hard-coded reserved-name list: a table may legitimately
  // own a column called `value` or `id`, and the grid now shows it whenever no fixed column of that
  // name is built. A name filtered here but shown there is a column the user can sort but never
  // fill (AddRowForm silently renders an empty cell for it).
  const getDynamicColumns = useCallback(() => {
    return getUniqueColumns().filter(col => !fixedColumnFields.includes(col.field));
  }, [getUniqueColumns, fixedColumnFields]);

  // Convert data to CSV format
  const convertToCSV = useCallback((rowsToExport: DataSourceItemRow[], columnsToUse: ColumnDefinition[]) => {
    // Skip only what the base columns already carry. Filtering on the view's WHOLE fixed set would
    // drop `value` / `array_index`, which have no base column - and `value` is the entire content
    // of a table drilled into an array of primitives.
    const exportedColumns = columnsToUse.filter(col => !rowLevelFields.includes(col.field));

    // A base column exists only where the view renders that lane. During nested navigation
    // `row.priority` / `row.created_at` belong to the PARENT row, which the grid deliberately does
    // not show, and emitting them next to the item's own fields of the same name produced two
    // identically-named columns.
    const baseHeaders = [
      ...(rowLevelFields.includes('id') ? ['ID'] : []),
      ...(rowLevelFields.includes('priority') ? ['Priority'] : []),
      // The zone goes in the HEADER, not beside every value: this file leaves the product, and
      // the reader who opens it has no display preference of ours. The cell keeps the readable
      // form the grid shows; the column says once which zone that form is in.
      ...(rowLevelFields.includes('created_at')
        ? [`Created At (${displayZoneFor(new Date())})`]
        : []),
    ];
    // Headers are escaped like the values: a column name may legitimately carry a comma
    // (`Revenue, USD`), which would otherwise split the header row.
    const headers = [...baseHeaders, ...exportedColumns.map(col => col.header_name)].map(escapeCsv);

    // Data rows (excluding tenant_id and data_source_id for security)
    const dataRows = rowsToExport.map(row => {
      // Escaped like the data values: a formatted date carries commas ("Dec 31, 2099, 00:00"),
      // so an unquoted base column splits one row across three CSV fields.
      const baseValues = [
        // Exactly one column per row-level field. Where `id` is NOT one it is the item's own field
        // and reaches the file as a data column, so a base ID beside it would put the same name in
        // twice - and in JSON would silently overwrite one with the other.
        ...(rowLevelFields.includes('id') ? [escapeCsv(String(displayIdOf(row)))] : []),
        ...(rowLevelFields.includes('priority') ? [escapeCsv(String(row.priority ?? ''))] : []),
        ...(rowLevelFields.includes('created_at')
          ? [escapeCsv(row.created_at ? formatUtcDateTime(row.created_at) : '')]
          : []),
      ];

      const dataValues = exportedColumns
        .map(col => {
          const field = col.field.replace('data.', '');
          const value = row.data?.[field];

          if (value === null || value === undefined) {
            return '';
          }

          // A media cell exports as its file name, not as the serialized reference: a column of
          // {"_type":"file","url":...} blobs is unreadable in a spreadsheet. Everything else keeps
          // its previous encoding - in particular a url/text column must export its own text, not
          // the file name a greedy parse would read out of it.
          const stringValue = cellDisplayText(value);

          // Escape quotes and commas for CSV. This used to be skipped for objects, so any
          // serialized value carrying a comma produced a malformed row.
          return escapeCsv(stringValue);
        });

      return [...baseValues, ...dataValues];
    });

    // Combine all
    const csvContent = [
      headers.join(','),
      ...dataRows.map(row => row.join(','))
    ].join('\n');

    return csvContent;
  }, [rowLevelFields]);

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
          selectedColumns.has(col.field)
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
          selectedColumns.has(col.field)
        );
      }

      // The metadata announces exactly the columns the data objects carry. Built from the
      // unfiltered set it advertised row-level columns that no row contained.
      const exportedColumns = columnsToUse.filter(col => !rowLevelFields.includes(col.field));

      const jsonData = {
        exportDate: new Date().toISOString(),
        dataSourceId: dataSourceId,
        totalRows: rowsToExport.length,
        exportedRows: selectedRows.size > 0 ? Array.from(selectedRows) : 'all',
        columns: exportedColumns.map(col => ({
          id: col.col_id,
          name: col.header_name,
          field: col.field,
          type: col.type
        })),
        // Same identity and same column split as the CSV: the two exports of one table must not
        // disagree about which row this is or which fields it has. A base key is written only
        // where that lane exists, so a data field of the same name can never overwrite it.
        data: rowsToExport.map(row => {
          const rowData: Record<string, unknown> = {};
          if (rowLevelFields.includes('id')) rowData.id = displayIdOf(row);
          if (rowLevelFields.includes('priority')) rowData.priority = row.priority;
          if (rowLevelFields.includes('created_at')) rowData.created_at = row.created_at;

          exportedColumns
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
  }, [dataSourceId, rows, columns, selectedRows, selectedColumns, getRowUniqueKey, downloadFile, addToast, rowLevelFields]);

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
          .filter(field => !rowLevelFields.includes(field))
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
  }, [dataSourceId, columns, selectedRows, selectedColumns, searchQuery, sortConfig, pagination, addToast, rowLevelFields]);

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
          .filter(field => !rowLevelFields.includes(field))
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
  }, [dataSourceId, columns, selectedRows, selectedColumns, addToast, rowLevelFields]);

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
