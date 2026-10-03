'use client';

import { useCallback, useMemo, useState } from 'react';
import type { DataSourceItemRow, PaginationState } from '../types';
import { getValueAtPath } from '../visualHelpers';
import type { SortConfig } from '../utils/dataTableUtils';
import { compareDisplayIds, displayIdOf, getDefaultSortConfig } from '../utils/dataTableUtils';
import { cellDisplayText as cellText } from '@/lib/datatable/assetValue';
import {
  URL_SEARCH_DEBOUNCE_MS,
  urlJson,
  useUrlSearchState,
  useUrlState,
  type UrlStateCodec,
} from '@/hooks/useUrlState';

/** The names a table page spells its view with in the address. */
export const TABLE_URL_KEYS = {
  search: 'q',
  sort: 'sort',
  filters: 'filters',
  page: 'page',
  pageSize: 'size',
} as const;

/**
 * A sort as `<column>:<asc|desc>`. The table's own default order is spelled by absence, like
 * "no sort": cycling a header back to it must leave a clean address, not the default written out.
 */
const sortCodec: UrlStateCodec<SortConfig | null> = {
  parse: (raw) => {
    const cut = raw.lastIndexOf(':');
    if (cut <= 0) return undefined;
    const direction = raw.slice(cut + 1);
    if (direction !== 'asc' && direction !== 'desc') return undefined;
    return { key: raw.slice(0, cut), direction };
  },
  serialize: (value) => {
    const fallback = getDefaultSortConfig();
    if (!value || (value.key === fallback.key && value.direction === fallback.direction)) return '';
    return `${value.key}:${value.direction}`;
  },
};

/** Column filters are text per column: anything else in the address is not a filter set. */
const columnFiltersCodec: UrlStateCodec<Record<string, string>> = (() => {
  const json = urlJson<Record<string, string>>(
    (value) =>
      typeof value === 'object' && value !== null && !Array.isArray(value)
      && Object.values(value).every((entry) => typeof entry === 'string'),
  );
  return {
    parse: json.parse,
    // Emptied filters are dropped, so a column typed in and cleared equals "no filters".
    serialize: (value) =>
      json.serialize(Object.fromEntries(Object.entries(value).filter(([, text]) => text !== ''))),
  };
})();

export interface UseSortingAndFilteringParams {
  rows: DataSourceItemRow[];
  fetchData: (page: number, pageSize: number, sortConfig?: SortConfig | null) => Promise<void>;
  pagination: PaginationState;
  dataSourceId?: number;
  /** True on a table's own page: the search, sort and column filters live in the address. */
  urlState?: boolean;
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
  urlState = false,
}: UseSortingAndFilteringParams): UseSortingAndFilteringReturn {
  const [sortConfig, setSortConfig] = useUrlState<SortConfig | null>(TABLE_URL_KEYS.sort, null, {
    codec: sortCodec,
    enabled: urlState,
  });
  const [searchQuery, setSearchQuery] = useUrlSearchState(TABLE_URL_KEYS.search, urlState);
  const [columnFilters, setColumnFilters] = useUrlState<Record<string, string>>(
    TABLE_URL_KEYS.filters,
    {},
    { codec: columnFiltersCodec, enabled: urlState, debounceMs: URL_SEARCH_DEBOUNCE_MS },
  );
  // Filters restored from the address open their panel: rows filtered by a text nobody can
  // see would read as missing data.
  const [showColumnFilters, setShowColumnFilters] = useState(
    () => Object.values(columnFilters).some((text) => text !== ''),
  );

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
  }, [dataSourceId, pagination.pageSize, fetchData, setSortConfig]);

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
