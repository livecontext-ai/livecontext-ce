'use client';

import { type Dispatch, type SetStateAction, useCallback, useEffect, useRef, useState } from 'react';
import type { ColumnDefinition, ColumnOrder, DataSourceItemRow, PaginationState, ServerFilters, SnapshotTableData } from '../types';
import { normalizeMappingSpec, resolveColumnType, type RawMappingSpecValue } from '@/utils/columnSpec';
import { encodeStepAliasForUrl } from '@/lib/utils/url-encoding';
import { authenticatedFetch } from '../utils/authenticatedFetch';
import {
  parsePaginationResponse,
  updatePaginationState,
  createEmptyPaginationUpdate,
  normalizeRows,
  parseErrorResponse,
  type SortConfig,
  type NormalizeRowContext,
} from '../utils/dataTableUtils';
import type {
  ColumnDefinition as BackendColumnDefinition,
  DetailedStepDataResponse,
  NodeType,
} from '@/lib/api/orchestrator/types';

export interface WorkflowContext {
  workflowId: string;
  runId: string;
  stepId?: number;
  stepAlias?: string;
  isAggregated?: boolean;
}

export interface UseDataFetchingParams {
  dataSourceId?: number;
  jsonPath?: string;
  workflowContext?: WorkflowContext | null;
  showIdColumn?: boolean;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
  setPagination: Dispatch<SetStateAction<PaginationState>>;
  /** Marketplace preview snapshot - skips all HTTP fetches when provided. */
  snapshotData?: SnapshotTableData;
}

export interface UseDataFetchingReturn {
  // State
  rows: DataSourceItemRow[];
  columns: ColumnDefinition[];
  tableLoading: boolean;
  loadingColumns: boolean;
  error: string | null;

  // Backend column definitions (node-specific)
  backendColumns: BackendColumnDefinition[] | null;
  nodeType: NodeType | null;

  // Setters
  setRows: React.Dispatch<React.SetStateAction<DataSourceItemRow[]>>;
  setColumns: React.Dispatch<React.SetStateAction<ColumnDefinition[]>>;
  setError: React.Dispatch<React.SetStateAction<string | null>>;

  // Actions
  fetchColumns: (force?: boolean) => Promise<void>;
  fetchData: (page?: number, pageSize?: number, sortConfig?: SortConfig | null, cursor?: string | null, serverFilters?: ServerFilters | null, append?: boolean) => Promise<void>;

  // Column order management (passed down for coordination)
  setColumnOrder: (order: ColumnOrder[]) => void;
}

const ORCHESTRATOR_URL = '/api/proxy';
const TENANT_ID = 'anonymous'; // Backend uses X-User-ID from JWT

/**
 * Map backend column type to local type
 */
function mapBackendType(backendType: string): 'text' | 'number' | 'date' | 'boolean' | 'json' {
  switch (backendType) {
    case 'NUMBER':
      return 'number';
    case 'BOOLEAN':
      return 'boolean';
    case 'DATETIME':
      return 'date';
    case 'JSON':
      return 'json';
    default:
      return 'text';
  }
}

/**
 * Navigate to a nested path in an object (supports array indices like "parts.0.body")
 */
function navigateToPath(obj: any, path: string): any {
  if (!path) return obj;
  const parts = path.split('.');
  let current = obj;
  for (const part of parts) {
    if (current == null) return undefined;
    if (Array.isArray(current)) {
      const index = parseInt(part, 10);
      if (!isNaN(index) && index >= 0 && index < current.length) {
        current = current[index];
        continue;
      }
    }
    if (typeof current === 'object' && part in current) {
      current = current[part];
    } else {
      return undefined;
    }
  }
  return current;
}

/**
 * Infer column type from a sample value (used for client-side column inference fallback)
 */
function inferType(value: unknown): string {
  if (value === null || value === undefined) return 'text';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string') {
    // Check for date patterns
    if (/^\d{4}-\d{2}-\d{2}/.test(value)) return 'date';
    return 'text';
  }
  if (typeof value === 'object') return 'json';
  return 'text';
}

/**
 * Hook for fetching data table columns and rows.
 * Handles DataSource, Workflow step, and Aggregated data contexts.
 * Merges the previously separate fetchData and fetchDataWithSort into a unified function.
 */
export function useDataFetching({
  dataSourceId,
  jsonPath,
  workflowContext,
  showIdColumn = false,
  addToast,
  setPagination,
  snapshotData,
}: UseDataFetchingParams): UseDataFetchingReturn {
  const isSnapshot = !!snapshotData;
  const [rows, setRows] = useState<DataSourceItemRow[]>(() => snapshotData?.rows ?? []);
  const [columns, setColumns] = useState<ColumnDefinition[]>(() => snapshotData?.columns ?? []);
  const [columnOrder, setColumnOrder] = useState<ColumnOrder[]>(() => (
    snapshotData?.columnOrder
      ?? (snapshotData?.columns ?? []).map((c, i) => ({ field: c.field, order: i }))
  ));
  const [backendColumns, setBackendColumns] = useState<BackendColumnDefinition[] | null>(null);
  const [nodeType, setNodeType] = useState<NodeType | null>(null);
  const [tableLoading, setTableLoading] = useState(false);
  const [loadingColumns, setLoadingColumns] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Snapshot mode: seed pagination once and keep it static.
  useEffect(() => {
    if (!isSnapshot) return;
    const total = snapshotData?.rows.length ?? 0;
    setPagination(prev => ({
      ...prev,
      currentPage: 1,
      totalItems: total,
      totalPages: 1,
      nextCursor: null,
      hasMore: false,
    }));
  }, [isSnapshot, snapshotData, setPagination]);

  // Ref to prevent duplicate column fetches
  const fetchColumnsCalledRef = useRef<string>('');

  // Helper to create normalize context
  const createNormalizeContext = useCallback((): NormalizeRowContext => ({
    tenantId: TENANT_ID,
    jsonPath,
    workflowContext,
    dataSourceId,
  }), [jsonPath, workflowContext, dataSourceId]);

  // Helper to initialize column order
  const initializeColumnOrder = useCallback((dynamicColumns: ColumnDefinition[], fixedCols: string[]) => {
    const allCols = [...fixedCols, ...dynamicColumns.map(col => col.field)];
    const order = allCols.map((field, index) => ({ field, order: index }));
    setColumnOrder(order);
  }, []);

  // Helper to transform API columns to ColumnDefinition
  const transformApiColumns = useCallback((apiColumns: any[]): ColumnDefinition[] => {
    return apiColumns.map((col: any) => ({
      col_id: col.col_id || col.key || col.field,
      field: col.key || col.field,
      header_name: col.header_name || col.key || col.field,
      type: (col.type || 'text') as 'text' | 'number' | 'date' | 'boolean' | 'json',
      editable: col.editable !== false,
      sortable: col.sortable !== false,
      filterable: col.filterable !== false,
      isNavigable: col.hasChildren || col.is_navigable || false,
    }));
  }, []);

  /**
   * Fetch columns definition from API or infer from data
   * @param force - If true, bypass the duplicate fetch check and force a refresh
   */
  const fetchColumns = useCallback(async (force: boolean = false) => {
    // Snapshot mode - columns seeded at init, no HTTP fetch.
    if (isSnapshot) return;

    const configKey = JSON.stringify({ dataSourceId, jsonPath, workflowContext });

    // Avoid duplicate fetches for same config (use ref to check current state)
    // Unless force=true (used after adding/deleting columns)
    if (!force && fetchColumnsCalledRef.current === configKey) {
      return;
    }

    fetchColumnsCalledRef.current = configKey;
    setLoadingColumns(true);

    try {
      // Workflow context - fetch from workflow endpoints
      if (workflowContext) {
        await fetchWorkflowColumns();
        return;
      }

      // DataSource context - fetch from datasource endpoints
      if (dataSourceId) {
        await fetchDataSourceColumns();
        return;
      }

      // No context - empty columns
      setColumns([]);
    } catch (err) {
      console.error('Error fetching columns:', err);
      setError('Failed to load column definitions');
      addToast({
        type: 'error',
        title: 'Error Loading Columns',
        message: 'Failed to load column definitions',
      });
    } finally {
      setLoadingColumns(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataSourceId, jsonPath, workflowContext, addToast]);

  // Fetch columns for workflow context
  const fetchWorkflowColumns = useCallback(async () => {
    if (!workflowContext) return;

    const { workflowId, runId, stepId, stepAlias, isAggregated } = workflowContext;

    // Case 1: stepId provided - fetch columns from step's output storage
    if (stepId) {
      // Use nested columns endpoint - works for both root (empty path) and nested paths
      const url = `${ORCHESTRATOR_URL}/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/columns/nested?path=${encodeURIComponent(jsonPath || '')}`;
      const response = await authenticatedFetch(url);

      if (response.ok) {
        const nestedColumns = await response.json();
        if (Array.isArray(nestedColumns) && nestedColumns.length > 0) {
          const dynamicColumns = transformApiColumns(nestedColumns);
          setColumns(dynamicColumns);
          initializeColumnOrder(dynamicColumns, showIdColumn ? ['id'] : []);
          return;
        }
      }

      // Fallback: empty columns if endpoint fails
      setColumns([]);
    }

    // Case 2: Aggregated steps
    if (isAggregated) {
      const response = await authenticatedFetch(
        `${ORCHESTRATOR_URL}/v2/workflows/dag/instances/${runId}/steps/aggregated/columns/nested?path=${encodeURIComponent(jsonPath || '')}`
      );

      if (response.ok) {
        const apiColumns = await response.json();
        if (Array.isArray(apiColumns)) {
          const dynamicColumns = transformApiColumns(apiColumns);
          setColumns(dynamicColumns);
          initializeColumnOrder(dynamicColumns, ['id']);
          return;
        }
      }
    }

    // Case 3: Step alias - always use detailed endpoint (columns + rows from same source)
    if (stepAlias) {
      const detailedResponse = await authenticatedFetch(
        `${ORCHESTRATOR_URL}/workflows/${workflowId}/runs/${runId}/steps/alias/${encodeStepAliasForUrl(stepAlias)}/output/detailed?limit=${jsonPath ? 100 : 1}`
      );

      if (!detailedResponse.ok) {
        console.warn(`[useDataFetching] Detailed endpoint failed for stepAlias=${stepAlias}`);
        setColumns([]);
        return;
      }

      const detailed: DetailedStepDataResponse = await detailedResponse.json();

      if (jsonPath) {
        // Navigate into jsonPath within each row and infer columns
        const rows = detailed.rows || [];

        // Context fields (epoch, split, iteration) are already visible at root level.
        // No need to repeat them during nested navigation - just show the data + id.

        // Collect data keys from navigated content
        const allKeys = new Set<string>();
        rows.forEach((rowData: Record<string, any>) => {
          const nestedData = navigateToPath(rowData, jsonPath);
          if (nestedData === undefined) return;

          if (Array.isArray(nestedData)) {
            nestedData.forEach((item: any) => {
              if (item && typeof item === 'object' && !Array.isArray(item)) {
                Object.keys(item).forEach(key => allKeys.add(key));
              } else {
                allKeys.add('value');
              }
            });
          } else if (typeof nestedData === 'object' && nestedData !== null) {
            Object.keys(nestedData).forEach(key => allKeys.add(key));
          } else {
            allKeys.add('value');
          }
        });

        // Filter out internal _ prefixed keys (context injection markers)
        const dataColumns: ColumnDefinition[] = Array.from(allKeys)
          .filter(key => !key.startsWith('_'))
          .map(key => ({
            col_id: key,
            field: key,
            header_name: key,
            type: 'text' as const,
            editable: false,
            sortable: true,
            filterable: true,
            isNavigable: false,
          }));

        setColumns(dataColumns);
        initializeColumnOrder(dataColumns, showIdColumn ? ['id'] : []);
        return;
      }

      // No jsonPath - use backend columns directly
      if (detailed.columns && detailed.columns.length > 0) {
        setBackendColumns(detailed.columns);
        setNodeType(detailed.nodeType);

        const dynamicColumns: ColumnDefinition[] = detailed.columns.map((col) => ({
          col_id: col.field,
          field: col.field,
          header_name: col.header,
          type: mapBackendType(col.type),
          editable: false,
          sortable: col.sortable,
          filterable: col.filterable,
          isNavigable: col.renderType === 'JSON_NAVIGABLE',
          renderType: col.renderType,
          width: col.width,
          expandable: col.expandable,
        }));

        setColumns(dynamicColumns);
        initializeColumnOrder(dynamicColumns, dynamicColumns.map(c => c.field));
        return;
      }

      console.warn(`[useDataFetching] No columns from detailed endpoint for stepAlias=${stepAlias}`);
      setColumns([]);
    }

    // Fallback: empty columns
    setColumns([]);
  }, [workflowContext, jsonPath, showIdColumn, transformApiColumns, initializeColumnOrder]);

  // Fetch columns for DataSource context
  const fetchDataSourceColumns = useCallback(async () => {
    if (!dataSourceId) return;

    // Case 1: Nested path
    if (jsonPath) {
      // Try backend nested columns endpoint first
      try {
        const response = await authenticatedFetch(
          `/api/proxy/data-sources/${dataSourceId}/columns/nested?path=${encodeURIComponent(jsonPath)}`
        );

        if (response.ok) {
          const nestedColumns = await response.json();
          if (Array.isArray(nestedColumns) && nestedColumns.length > 0) {
            const dynamicColumns = transformApiColumns(nestedColumns);
            setColumns(dynamicColumns);
            const fixedCols = showIdColumn
              ? ['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value']
              : ['checkbox', 'priority', 'created_at', 'array_index', 'value'];
            initializeColumnOrder(dynamicColumns, fixedCols);
            return;
          }
        }
      } catch (e) {
        console.warn('[fetchDataSourceColumns] Nested columns endpoint failed:', e);
      }

      // Fallback: infer columns client-side from a sample of nested data
      console.warn('[fetchDataSourceColumns] Backend nested columns empty for path:', jsonPath, '- trying client-side inference');
      try {
        const dataResponse = await authenticatedFetch(
          `/api/proxy/data-sources/${dataSourceId}/items?limit=10&page=1`
        );
        if (dataResponse.ok) {
          const dataResult = await dataResponse.json();
          const rows = dataResult.rowData || [];
          const allKeys = new Set<string>();
          const keyTypes = new Map<string, string>();

          for (const row of rows) {
            const rowData = row.data;
            if (!rowData || typeof rowData !== 'object') continue;

            // Navigate to the nested path within each row's data
            let nested = rowData;
            for (const segment of jsonPath.split('.')) {
              if (nested == null || typeof nested !== 'object') { nested = undefined; break; }
              // Handle JSON strings: if the value is a string, try to parse it
              const val = Array.isArray(nested) ? nested[parseInt(segment, 10)] : nested[segment];
              if (typeof val === 'string') {
                try { nested = JSON.parse(val); } catch { nested = undefined; }
              } else {
                nested = val;
              }
            }

            if (nested == null) continue;

            if (Array.isArray(nested)) {
              // Mark as array data so the Index column shows
              allKeys.add('array_index');
              keyTypes.set('array_index', 'number');
              for (const item of nested) {
                if (item && typeof item === 'object' && !Array.isArray(item)) {
                  for (const key of Object.keys(item)) {
                    allKeys.add(key);
                    if (!keyTypes.has(key)) keyTypes.set(key, inferType(item[key]));
                  }
                } else {
                  allKeys.add('value');
                  if (!keyTypes.has('value')) keyTypes.set('value', 'text');
                }
              }
            } else if (typeof nested === 'object') {
              for (const key of Object.keys(nested)) {
                allKeys.add(key);
                if (!keyTypes.has(key)) keyTypes.set(key, inferType(nested[key]));
              }
            } else {
              allKeys.add('value');
              if (!keyTypes.has('value')) keyTypes.set('value', 'text');
            }
          }

          if (allKeys.size > 0) {
            const inferredColumns: ColumnDefinition[] = Array.from(allKeys)
              .filter(key => !key.startsWith('_'))
              .map(key => ({
                col_id: key,
                field: key,
                header_name: key,
                type: (keyTypes.get(key) || 'text') as 'text' | 'number' | 'date' | 'boolean' | 'json',
                editable: false,
                sortable: true,
                filterable: true,
                isNavigable: false,
              }));
            setColumns(inferredColumns);
            const fixedCols = showIdColumn
              ? ['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value']
              : ['checkbox', 'priority', 'created_at', 'array_index', 'value'];
            initializeColumnOrder(inferredColumns, fixedCols);
            return;
          }
        }
      } catch (e) {
        console.warn('[fetchDataSourceColumns] Client-side column inference failed:', e);
      }

      // Nothing found - set empty columns (don't fall through to root columns)
      setColumns([]);
      return;
    }

    // Case 2: Normal root view.
    const fixedCols = showIdColumn
      ? ['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value']
      : ['checkbox', 'priority', 'created_at', 'array_index', 'value'];

    // Primary source: the /data-sources list. It carries the saved column_order and
    // display config, but it is org-strict (WHERE organization_id = ?), so a table
    // the caller owns yet that is tagged to a different workspace - or one whose
    // mapping_spec is empty - produces no user columns here.
    const dsResponse = await authenticatedFetch(`/api/proxy/data-sources`);

    if (dsResponse.ok) {
      const dataSources = await dsResponse.json();
      const currentDS = dataSources.find((ds: any) => ds.id === dataSourceId);

      if (currentDS) {
        const mappingSpec = currentDS.mapping_spec as Record<string, RawMappingSpecValue> | undefined;
        const normalized = normalizeMappingSpec(mappingSpec);

        const dynamicColumns = Object.values(normalized).map(spec => ({
          col_id: spec.path,
          field: spec.path,
          header_name: spec.display?.label || spec.key,
          type: spec.type as any,
          editable: true,
          sortable: spec.type !== 'json',
          filterable: true,
          isNavigable: spec.structure !== 'scalar' || spec.type === 'json',
          displayConfig: spec.display,
          structure: spec.structure,
        }));

        if (dynamicColumns.length > 0) {
          setColumns(dynamicColumns);

          // Use saved column order or default
          if (currentDS.column_order?.length > 0) {
            setColumnOrder(currentDS.column_order);
          } else {
            initializeColumnOrder(dynamicColumns, fixedCols);
          }
          return;
        }
      }
    }

    // Fallback: the table is absent from the org-strict list (owned but tagged to
    // another workspace) or its mapping_spec is empty. The per-id columns endpoint
    // resolves columns with the SAME owner-permissive scope as the rows
    // (resolveAccessibleTenantId) and infers them from the JSON data keys when
    // mapping_spec is empty - so the grid never renders the system columns alone
    // while its rows are populated.
    let fallbackColumns: ColumnDefinition[] = [];
    const colResponse = await authenticatedFetch(`/api/proxy/data-sources/${dataSourceId}/columns`);
    if (colResponse.ok) {
      const apiColumns = await colResponse.json();
      if (Array.isArray(apiColumns)) {
        // The endpoint always prepends the system columns (id/priority/created_at);
        // those are supplied separately via fixedCols, so drop them here.
        const SYSTEM_FIELDS = new Set(['id', 'priority', 'created_at']);
        fallbackColumns = apiColumns
          .filter((col) => !SYSTEM_FIELDS.has(String(col.field ?? col.col_id)))
          .map((col) => {
            const field = String(col.field ?? col.col_id);
            const structure = typeof col.structure === 'string' ? col.structure.toLowerCase() : 'scalar';
            return {
              col_id: String(col.col_id ?? field),
              field,
              header_name: col.header_name || field,
              type: resolveColumnType(String(col.type ?? 'text')),
              editable: col.editable !== false,
              sortable: col.sortable !== false,
              filterable: col.filterable !== false,
              isNavigable: structure !== 'scalar',
              displayConfig: col.display,
              structure: structure as ColumnDefinition['structure'],
            };
          });
      }
    }

    // Publish the result unconditionally (empty included) so a forced refetch that
    // finds no user columns clears any stale ones - matching the semantics the
    // primary branch had before it gained an early return.
    setColumns(fallbackColumns);
    if (fallbackColumns.length > 0) {
      initializeColumnOrder(fallbackColumns, fixedCols);
    }
  }, [dataSourceId, jsonPath, showIdColumn, transformApiColumns, initializeColumnOrder]);

  /**
   * Unified data fetch function - handles both sorted and unsorted queries
   * Replaces both fetchData and fetchDataWithSort from original controller
   */
  const fetchData = useCallback(async (
    page: number = 1,
    pageSize: number = 20,
    sortConfig: SortConfig | null = null,
    cursor: string | null = null,
    serverFilters: ServerFilters | null = null,
    append: boolean = false,
  ) => {
    // Snapshot mode - rows seeded at init, no HTTP fetch.
    if (isSnapshot) return;

    try {
      setTableLoading(true);
      setError(null);

      const context = createNormalizeContext();

      // Workflow context
      if (workflowContext) {
        await fetchWorkflowData(page, pageSize, sortConfig, context, serverFilters, append);
        return;
      }

      // DataSource context
      if (dataSourceId) {
        await fetchDataSourceData(page, pageSize, sortConfig, cursor, context);
        return;
      }

      // No context - empty data
      setRows([]);
      setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
    } catch (err) {
      console.error('Error fetching data:', err);
      setError('Failed to load data');
    } finally {
      setTableLoading(false);
    }
  }, [workflowContext, dataSourceId, createNormalizeContext, isSnapshot]);

  // Fetch data for workflow context
  const fetchWorkflowData = useCallback(async (
    page: number,
    pageSize: number,
    sortConfig: SortConfig | null,
    context: NormalizeRowContext,
    serverFilters: ServerFilters | null = null,
    append: boolean = false,
  ) => {
    if (!workflowContext) return;

    // Append mode (infinite scroll): preserve existing rows and add the new page on top.
    // The first page (page=1) still replaces, so filter changes / re-mounts reset cleanly.
    const writeRows = (next: DataSourceItemRow[]) => {
      if (append && page > 1) {
        setRows(prev => [...prev, ...next]);
      } else {
        setRows(next);
      }
    };

    const { workflowId, runId, stepId, stepAlias, isAggregated } = workflowContext;

    // Case 1: stepId provided - fetch output data from step's storage
    if (stepId) {
      // Use nested endpoint for both root (empty path) and nested paths
      // The nested endpoint with empty path returns root level data
      let url = jsonPath
        ? `${ORCHESTRATOR_URL}/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/items/nested?path=${encodeURIComponent(jsonPath)}&limit=${pageSize}&page=${page}`
        : `${ORCHESTRATOR_URL}/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/items?limit=${pageSize}&page=${page}`;

      if (sortConfig) {
        url += `&sortBy=${sortConfig.key}&sortOrder=${sortConfig.direction}`;
      }

      const response = await authenticatedFetch(url);

      if (!response.ok) {
        if (response.status === 404) {
          setRows([]);
          setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
          return;
        }
        throw new Error('Failed to fetch step output data');
      }

      const responseData = await response.json();
      const normalizedRows = normalizeRows(responseData.rowData || [], context, { isWorkflowStep: true });
      writeRows(normalizedRows);

      const parsed = parsePaginationResponse(responseData, pageSize, normalizedRows.length);
      setPagination(prev => updatePaginationState(prev, parsed, page));
      return;
    }

    // Case 2: Aggregated
    if (isAggregated) {
      let url = `${ORCHESTRATOR_URL}/v2/workflows/dag/instances/${runId}/steps/aggregated/nested?path=${encodeURIComponent(jsonPath || '')}&limit=${pageSize}&page=${page}`;
      if (sortConfig) {
        url += `&sortBy=${sortConfig.key}&sortOrder=${sortConfig.direction}`;
      }

      const response = await authenticatedFetch(url);

      if (!response.ok) {
        if (response.status === 404) {
          setRows([]);
          setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
          return;
        }
        throw new Error('Failed to fetch aggregated steps data');
      }

      const responseData = await response.json();
      const normalizedRows = normalizeRows(responseData.rowData || [], context, {
        isWorkflowStep: true,
        isAggregated: true,
      });
      writeRows(normalizedRows);

      const parsed = parsePaginationResponse(responseData, pageSize, normalizedRows.length);
      setPagination(prev => updatePaginationState(prev, parsed, page));
      return;
    }

    // Case 3: Step alias - always use detailed endpoint (columns + rows from same source)
    if (stepAlias) {
      let detailedUrl = `${ORCHESTRATOR_URL}/workflows/${workflowId}/runs/${runId}/steps/alias/${encodeStepAliasForUrl(stepAlias)}/output/detailed?limit=${pageSize}&page=${page}`;
      if (serverFilters?.status) {
        detailedUrl += `&status=${encodeURIComponent(serverFilters.status)}`;
      }
      if (serverFilters?.epoch != null) {
        detailedUrl += `&epoch=${encodeURIComponent(String(serverFilters.epoch))}`;
      }
      const detailedResponse = await authenticatedFetch(detailedUrl);

      if (!detailedResponse.ok) {
        if (detailedResponse.status === 404) {
          setRows([]);
          setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
          return;
        }
        throw new Error('Failed to fetch detailed step data');
      }

      const detailed: DetailedStepDataResponse = await detailedResponse.json();
      const detailedRows = detailed.rows || [];

      if (jsonPath) {
        // Navigate into jsonPath within each row from ALL rows
        const normalizedRows: DataSourceItemRow[] = [];
        // Sequential ID for sub-table rows. In append mode, offset by page so the synthetic
        // IDs from a later page can't collide with earlier-page IDs (used as React keys and
        // as selection keys). A 100000-wide stride is plenty: detailed page size is capped
        // at 500 and each row expands to at most ~tens of nested children in practice.
        let seqId = (append && page > 1) ? page * 100000 + 1 : 1;

        detailedRows.forEach((rowData: Record<string, any>, rowIndex: number) => {
          const nestedData = navigateToPath(rowData, jsonPath);

          if (nestedData === undefined || nestedData === null) {
            return;
          } else if (Array.isArray(nestedData)) {
            nestedData.forEach((item: any, itemIndex: number) => {
              const itemData = typeof item === 'object' && item !== null ? item : { value: item };
              const rowId = seqId++;
              normalizedRows.push({
                id: rowId,
                data_source_id: 0,
                tenant_id: TENANT_ID,
                data: { ...itemData, id: rowId, array_index: itemIndex },
                priority: 0,
                created_at: rowData.startTime || new Date().toISOString(),
                updated_at: null,
                _jsonPath: jsonPath,
                _isWorkflowStep: true,
              });
            });
          } else if (typeof nestedData === 'object') {
            const rowId = seqId++;
            normalizedRows.push({
              id: rowId,
              data_source_id: 0,
              tenant_id: TENANT_ID,
              data: { ...nestedData, id: rowId },
              priority: 0,
              created_at: rowData.startTime || new Date().toISOString(),
              updated_at: null,
              _jsonPath: jsonPath,
              _isWorkflowStep: true,
            });
          } else {
            const rowId = seqId++;
            normalizedRows.push({
              id: rowId,
              data_source_id: 0,
              tenant_id: TENANT_ID,
              data: { value: nestedData, id: rowId },
              priority: 0,
              created_at: rowData.startTime || new Date().toISOString(),
              updated_at: null,
              _jsonPath: jsonPath,
              _isWorkflowStep: true,
            });
          }
        });

        writeRows(normalizedRows);
        if (detailed.pagination) {
          const totalItems = detailed.pagination.totalRows || 0;
          const totalPages = Math.ceil(totalItems / pageSize) || 1;
          setPagination(prev => ({
            ...prev,
            currentPage: page,
            pageSize,
            totalItems,
            totalPages,
            hasMore: detailed.pagination.hasMore ?? (page < totalPages),
          }));
        }
        return;
      }

      // No jsonPath - use rows directly
      const normalizedRows: DataSourceItemRow[] = detailedRows.map((rowData: Record<string, any>, rowIndex: number) => ({
        id: rowData.id || rowIndex + 1,
        data_source_id: 0,
        tenant_id: TENANT_ID,
        data: rowData,
        priority: 0,
        created_at: rowData.startTime || new Date().toISOString(),
        updated_at: null,
        _jsonPath: '',
        _isWorkflowStep: true,
      }));

      writeRows(normalizedRows);
      if (detailed.pagination) {
        const totalItems = detailed.pagination.totalRows || 0;
        const totalPages = Math.ceil(totalItems / pageSize) || 1;
        setPagination(prev => ({
          ...prev,
          currentPage: page,
          pageSize,
          totalItems,
          totalPages,
          hasMore: detailed.pagination.hasMore ?? (page < totalPages),
        }));
      }
      return;
    }

    // No stepAlias provided - cannot fetch workflow step data
    console.warn('[useDataFetching] No stepAlias provided for workflow context');
    setRows([]);
    setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
  }, [workflowContext, jsonPath]);

  // Fetch data for DataSource context
  const fetchDataSourceData = useCallback(async (
    page: number,
    pageSize: number,
    sortConfig: SortConfig | null,
    cursor: string | null,
    context: NormalizeRowContext
  ) => {
    if (!dataSourceId) {
      setRows([]);
      setPagination(prev => ({ ...prev, ...createEmptyPaginationUpdate() }));
      return;
    }

    // When jsonPath is set, first try the backend nested endpoint.
    // If it returns empty (e.g., data stored as JSON string), fall back to
    // client-side navigation: fetch root data and extract nested path in JS.
    if (jsonPath) {
      let url = `/api/proxy/data-sources/${dataSourceId}/items/nested?path=${encodeURIComponent(jsonPath)}&limit=${pageSize}&page=${page}`;
      if (sortConfig) {
        url += `&sortBy=${encodeURIComponent(sortConfig.key)}&sortOrder=${sortConfig.direction}`;
      }

      const response = await authenticatedFetch(url);

      if (response.ok) {
        const responseData = await response.json();
        const backendRows = responseData.rowData || [];

        if (backendRows.length > 0) {
          // Backend handled it successfully
          const normalizedRows = normalizeRows(backendRows, context);
          setRows(normalizedRows);
          const parsed = parsePaginationResponse(responseData, pageSize, normalizedRows.length);
          setPagination(prev => updatePaginationState(prev, parsed, page));
          return;
        }
      }

      // Backend returned empty or failed - try client-side navigation fallback.
      // Fetch ALL root items (no pagination) because nested expansion happens client-side.
      // The backend can't paginate nested items when data is stored as JSON strings.
      console.warn('[fetchDataSourceData] Backend nested data empty for path:', jsonPath, '- trying client-side navigation');
      const rootUrl = `/api/proxy/data-sources/${dataSourceId}/items?limit=1000&page=1`;
      const rootResponse = await authenticatedFetch(rootUrl);

      if (!rootResponse.ok) {
        const errorMessage = await parseErrorResponse(rootResponse, 'Failed to fetch data');
        throw new Error(errorMessage);
      }

      const rootData = await rootResponse.json();
      const rootRows = rootData.rowData || [];
      const allNestedRows: DataSourceItemRow[] = [];

      for (const row of rootRows) {
        const rowData = row.data;
        if (!rowData || typeof rowData !== 'object') continue;

        // Navigate to the nested path, handling JSON strings along the way
        let nested: any = rowData;
        for (const segment of jsonPath.split('.')) {
          if (nested == null || typeof nested !== 'object') { nested = undefined; break; }
          const val = Array.isArray(nested) ? nested[parseInt(segment, 10)] : nested[segment];
          if (typeof val === 'string') {
            try { nested = JSON.parse(val); } catch { nested = undefined; }
          } else {
            nested = val;
          }
        }

        if (nested == null) continue;

        const rowId = row.id || allNestedRows.length + 1;

        if (Array.isArray(nested)) {
          nested.forEach((item: any, idx: number) => {
            const itemData = (item && typeof item === 'object' && !Array.isArray(item)) ? item : { value: item };
            allNestedRows.push({
              id: rowId,
              data_source_id: row.data_source_id ?? dataSourceId ?? 0,
              tenant_id: row.tenant_id ?? TENANT_ID,
              data: { ...itemData, array_index: idx },
              priority: row.priority ?? 0,
              created_at: row.created_at ?? new Date().toISOString(),
              updated_at: row.updated_at ?? null,
              _jsonPath: jsonPath,
            });
          });
        } else if (typeof nested === 'object') {
          allNestedRows.push({
            id: rowId,
            data_source_id: row.data_source_id ?? dataSourceId ?? 0,
            tenant_id: row.tenant_id ?? TENANT_ID,
            data: nested,
            priority: row.priority ?? 0,
            created_at: row.created_at ?? new Date().toISOString(),
            updated_at: row.updated_at ?? null,
            _jsonPath: jsonPath,
          });
        } else {
          allNestedRows.push({
            id: rowId,
            data_source_id: row.data_source_id ?? dataSourceId ?? 0,
            tenant_id: row.tenant_id ?? TENANT_ID,
            data: { value: nested },
            priority: row.priority ?? 0,
            created_at: row.created_at ?? new Date().toISOString(),
            updated_at: row.updated_at ?? null,
            _jsonPath: jsonPath,
          });
        }
      }

      // Client-side pagination: slice the expanded nested rows for the requested page
      const startIdx = (page - 1) * pageSize;
      const endIdx = startIdx + pageSize;
      const pageRows = allNestedRows.slice(startIdx, endIdx);
      const totalItems = allNestedRows.length;

      setRows(pageRows);
      setPagination(prev => ({
        ...prev,
        currentPage: page,
        pageSize,
        totalItems,
        totalPages: Math.ceil(totalItems / pageSize) || 1,
        hasMore: endIdx < totalItems,
      }));
      return;
    }

    // No jsonPath - normal root data fetch
    let url = `/api/proxy/data-sources/${dataSourceId}/items?limit=${pageSize}&page=${page}`;
    if (cursor) {
      url += `&cursor=${encodeURIComponent(cursor)}`;
    }
    if (sortConfig) {
      url += `&sortBy=${encodeURIComponent(sortConfig.key)}&sortOrder=${sortConfig.direction}`;
    }

    const response = await authenticatedFetch(url);

    if (!response.ok) {
      const errorMessage = await parseErrorResponse(response, 'Failed to fetch data');
      console.error('[fetchData] Error response:', response.status, errorMessage);
      throw new Error(errorMessage);
    }

    const responseData = await response.json();
    const normalizedRows = normalizeRows(responseData.rowData || [], context);
    setRows(normalizedRows);

    const parsed = parsePaginationResponse(responseData, pageSize, normalizedRows.length);
    setPagination(prev => updatePaginationState(prev, parsed, page));
  }, [dataSourceId, jsonPath]);

  return {
    // State
    rows,
    columns,
    tableLoading,
    loadingColumns,
    error,

    // Backend column definitions (node-specific)
    backendColumns,
    nodeType,

    // Setters
    setRows,
    setColumns,
    setError,

    // Actions
    fetchColumns,
    fetchData,

    // For coordination
    setColumnOrder,
  };
}
