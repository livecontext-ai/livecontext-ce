'use client';

import { useCallback, useMemo, useState } from 'react';
import type { DataSourceItemRow, PaginationState } from '../types';
import { getValueAtPath } from '../visualHelpers';
import type { SortConfig } from '../utils/dataTableUtils';
import { compareDisplayIds, displayIdOf, getDefaultSortConfig } from '../utils/dataTableUtils';
import { cellDisplayText as cellText } from '@/lib/datatable/assetValue';

export interface UseSortingAndFilteringParams {
  rows: DataSourceItemRow[];
  fetchData: (page: number, pageSize: number, sortConfig?: SortConfig | null) => Promise<void>;
  pagination: PaginationState;
  dataSourceId?: number;
}

export interface UseSortingAndFilteringReturn {
  // State
  sortConfig: SortConfig | null;
  searchQuery: string;
  showColumnFilters: boolean;
  columnFilters: Record<string, string>;
  filteredRows: DataSourceItemRow[];

  // Setters
  setSortConfig: React.Dispatch<React.SetStateAction<SortConfig | null>>;
  setSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  setShowColumnFilters: React.Dispatch<React.SetStateAction<boolean>>;
  setColumnFilters: React.Dispatch<React.SetStateAction<Record<string, string>>>;

  // Actions
  handleSort: (key: string) => void;
  sortData: (data: DataSourceItemRow[]) => DataSourceItemRow[];
}

/**
 * Hook for managing sorting and filtering in the data table.
 */
export function useSortingAndFiltering({
  rows,
  fetchData,
  pagination,
  dataSourceId,
}: UseSortingAndFilteringParams): UseSortingAndFilteringReturn {
  const [sortConfig, setSortConfig] = useState<SortConfig | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showColumnFilters, setShowColumnFilters] = useState(false);
  const [columnFilters, setColumnFilters] = useState<Record<string, string>>({});

  // Helper to get field path
  const getFieldPath = (field: string) =>
    field.startsWith('data.') ? field.replace('data.', '') : field;

  /**
   * Sort data locally (used when server-side sorting is not available)
   */
  const sortData = useCallback((data: DataSourceItemRow[]) => {
    if (!sortConfig) return data;

    return [...data].sort((a, b) => {
      let aValue: any;
      let bValue: any;

      if (sortConfig.key === 'id') {
        // The id the row SHOWS (e.g. a nested log item's "21:3"), never the hidden React key.
        const order = compareDisplayIds(displayIdOf(a), displayIdOf(b));
        return sortConfig.direction === 'asc' ? order : -order;
      } else if (sortConfig.key === 'priority') {
        aValue = a.priority;
        bValue = b.priority;
      } else if (sortConfig.key === 'created_at') {
        aValue = new Date(a.created_at).getTime();
        bValue = new Date(b.created_at).getTime();
      } else if (sortConfig.key.startsWith('data.')) {
        const fieldPath = getFieldPath(sortConfig.key);
        aValue = getValueAtPath(a.data, fieldPath);
        bValue = getValueAtPath(b.data, fieldPath);
      } else {
        return 0;
      }

      // Handle null/undefined
      if (aValue === null || aValue === undefined) aValue = '';
      if (bValue === null || bValue === undefined) bValue = '';

      // String comparison. A media cell sorts by its file name: String() on the asset map gives
      // '[object Object]' for every row, which is not an ordering at all.
      const aStr = cellText(aValue).toLowerCase();
      const bStr = cellText(bValue).toLowerCase();

      if (aStr < bStr) return sortConfig.direction === 'asc' ? -1 : 1;
      if (aStr > bStr) return sortConfig.direction === 'asc' ? 1 : -1;
      return 0;
    });
  }, [sortConfig]);

  /**
   * Handle sort column click - cycle through asc -> desc -> default
   */
  const handleSort = useCallback((key: string) => {
    setSortConfig(prev => {
      let newConfig: SortConfig | null;

      if (prev && prev.key === key) {
        if (prev.direction === 'asc') {
          newConfig = { key, direction: 'desc' };
        } else if (prev.direction === 'desc') {
          newConfig = null;
        } else {
          newConfig = { key, direction: 'asc' };
        }
      } else {
        newConfig = { key, direction: 'asc' };
      }

      // Fetch with new sort config
      if (dataSourceId) {
        if (newConfig) {
          fetchData(1, pagination.pageSize, newConfig);
        } else {
          // Reset to default sort
          const defaultSort = getDefaultSortConfig();
          setSortConfig(defaultSort);
          fetchData(1, pagination.pageSize, defaultSort);
          return defaultSort;
        }
      }

      return newConfig;
    });
  }, [dataSourceId, pagination.pageSize, fetchData]);

  /**
   * Filter rows based on search query and column filters
   */
  const filteredRows = useMemo(() => {
    return rows.filter(row => {
      // Global search filter
      if (searchQuery) {
        const searchLower = searchQuery.toLowerCase();
        const matchesSearch = Object.values(row.data).some(value =>
          // Media cells match on their file name, so a search for "invoice" finds the row with
          // invoice.pdf instead of matching every asset row on "_type" or "disposition".
          cellText(value).toLowerCase().includes(searchLower)
        );
        if (!matchesSearch) return false;
      }

      // Column filters
      for (const [columnKey, filterValue] of Object.entries(columnFilters)) {
        if (!filterValue) continue;

        const cellValue = getValueAtPath(row.data, columnKey.replace('data.', ''));
        const cellValueStr = cellText(cellValue).toLowerCase();

        if (!cellValueStr.includes(filterValue.toLowerCase())) {
          return false;
        }
      }

      return true;
    });
  }, [rows, searchQuery, columnFilters]);

  return {
    // State
    sortConfig,
    searchQuery,
    showColumnFilters,
    columnFilters,
    filteredRows,

    // Setters
    setSortConfig,
    setSearchQuery,
    setShowColumnFilters,
    setColumnFilters,

    // Actions
    handleSort,
    sortData,
  };
}
