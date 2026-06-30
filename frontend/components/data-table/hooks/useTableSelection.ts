'use client';

import { useCallback, useState } from 'react';
import type { DataSourceItemRow } from '../types';

export interface UseTableSelectionParams {
  jsonPath?: string;
}

export interface UseTableSelectionReturn {
  // State
  selectedRows: Set<string>;
  selectedColumns: Set<string>;
  // Setters
  setSelectedRows: React.Dispatch<React.SetStateAction<Set<string>>>;
  // Functions
  getRowUniqueKey: (row: DataSourceItemRow) => string;
  toggleRowSelection: (row: DataSourceItemRow) => void;
  selectAllRows: (rows: DataSourceItemRow[]) => void;
  clearSelection: (rows: DataSourceItemRow[]) => void;
  toggleColumnSelection: (columnField: string) => void;
  clearColumnSelection: () => void;
}

/**
 * Hook for managing row and column selection in the data table.
 * Supports unique keys for array items (rowId-arrayIndex) vs regular rows (rowId).
 */
export function useTableSelection({ jsonPath }: UseTableSelectionParams): UseTableSelectionReturn {
  // State for multiple selection
  // For arrays, use unique keys (rowId-arrayIndex) instead of just rowId
  // This allows selecting individual sub-cells even if they share the same parent ID
  const [selectedRows, setSelectedRows] = useState<Set<string>>(new Set());
  const [selectedColumns, setSelectedColumns] = useState<Set<string>>(new Set());

  // Utility function to generate a unique key for a row
  // For arrays, uses rowId-arrayIndex; otherwise just rowId
  const getRowUniqueKey = useCallback((row: DataSourceItemRow): string => {
    if (jsonPath && row.data?.array_index !== undefined) {
      // For arrays, use rowId-arrayIndex to allow individual selection
      return `${row.id}-${row.data.array_index}`;
    }
    // For JSON objects or normal mode, use just rowId
    return String(row.id);
  }, [jsonPath]);

  // Toggle selection for a single row using unique keys
  const toggleRowSelection = useCallback((row: DataSourceItemRow) => {
    const uniqueKey = getRowUniqueKey(row);
    setSelectedRows(prev => {
      const newSet = new Set(prev);
      if (newSet.has(uniqueKey)) {
        newSet.delete(uniqueKey);
      } else {
        newSet.add(uniqueKey);
      }
      return newSet;
    });
  }, [getRowUniqueKey]);

  // Select all rows on the current page using their unique keys
  const selectAllRows = useCallback((rows: DataSourceItemRow[]) => {
    const currentPageRowKeys = rows.map(row => getRowUniqueKey(row));
    setSelectedRows(prev => {
      const newSet = new Set(prev);
      currentPageRowKeys.forEach(key => newSet.add(key));
      return newSet;
    });
  }, [getRowUniqueKey]);

  // Clear selection for rows on the current page only
  const clearSelection = useCallback((rows: DataSourceItemRow[]) => {
    const currentPageRowKeys = rows.map(row => getRowUniqueKey(row));
    setSelectedRows(prev => {
      const newSet = new Set(prev);
      currentPageRowKeys.forEach(key => newSet.delete(key));
      return newSet;
    });
  }, [getRowUniqueKey]);

  // Toggle selection for a column
  const toggleColumnSelection = useCallback((columnField: string) => {
    setSelectedColumns(prev => {
      const newSet = new Set(prev);
      if (newSet.has(columnField)) {
        newSet.delete(columnField);
      } else {
        newSet.add(columnField);
      }
      return newSet;
    });
  }, []);

  // Clear all column selections
  const clearColumnSelection = useCallback(() => {
    setSelectedColumns(new Set());
  }, []);

  return {
    // State
    selectedRows,
    selectedColumns,
    // Setters
    setSelectedRows,
    // Functions
    getRowUniqueKey,
    toggleRowSelection,
    selectAllRows,
    clearSelection,
    toggleColumnSelection,
    clearColumnSelection,
  };
}
