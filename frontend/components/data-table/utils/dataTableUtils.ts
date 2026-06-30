import type { DataSourceItemRow, PaginationState } from '../types';

/**
 * Parsed pagination response with normalized field names
 */
export interface PaginationParsed {
  totalItems: number;
  totalPages: number;
  hasMore: boolean;
  nextCursor: string | null;
}

/**
 * Context for row normalization
 */
export interface NormalizeRowContext {
  tenantId: string;
  jsonPath?: string;
  workflowContext?: {
    workflowId: string;
    runId: string;
    stepId?: number;
    stepAlias?: string;
    isAggregated?: boolean;
  } | null;
  dataSourceId?: number;
  index?: number;
}

/**
 * Sort configuration
 */
export interface SortConfig {
  key: string;
  direction: 'asc' | 'desc';
}

/**
 * Parse pagination response from API (handles both camelCase and snake_case)
 * This pattern was duplicated 15+ times in the original code.
 */
export function parsePaginationResponse(data: any, pageSize: number, fallbackCount?: number): PaginationParsed {
  const totalItems = data.row_count ?? data.rowCount ?? fallbackCount ?? 0;
  return {
    totalItems,
    totalPages: data.total_pages ?? data.totalPages ?? Math.ceil(totalItems / pageSize),
    hasMore: data.has_more !== undefined ? data.has_more : (data.hasMore ?? false),
    nextCursor: data.next_cursor ?? data.nextCursor ?? null,
  };
}

/**
 * Update pagination state with parsed response
 */
export function updatePaginationState(
  prev: PaginationState,
  parsed: PaginationParsed,
  currentPage: number
): PaginationState {
  return {
    ...prev,
    currentPage,
    totalItems: parsed.totalItems,
    totalPages: parsed.totalPages,
    nextCursor: parsed.nextCursor,
    hasMore: parsed.hasMore,
  };
}

/**
 * Create empty pagination state (for 404 or empty responses)
 * This pattern was duplicated 8+ times.
 */
export function createEmptyPaginationUpdate(): Partial<PaginationState> {
  return {
    currentPage: 1,
    totalItems: 0,
    totalPages: 0,
    nextCursor: null,
    hasMore: false,
  };
}

/**
 * Navigate to nested path in object
 * This pattern was duplicated 3+ times.
 */
export function navigateToPath(obj: any, path: string): any {
  if (!path) return obj;
  const parts = path.split('.');
  let current = obj;
  for (const part of parts) {
    if (current && typeof current === 'object' && part in current) {
      current = current[part];
    } else {
      return undefined;
    }
  }
  return current;
}

/**
 * Extract data at JSON path and normalize to row data format
 * Handles arrays, objects, and primitives.
 */
export function extractDataAtPath(rowData: any, jsonPath: string): any {
  if (!jsonPath || jsonPath === '') return rowData;

  const pathSegments = jsonPath.split('.');
  let extractedData = rowData;

  for (const segment of pathSegments) {
    if (extractedData && typeof extractedData === 'object' && segment in extractedData) {
      extractedData = extractedData[segment];
    } else {
      return null;
    }
  }

  if (extractedData === null || extractedData === undefined) {
    return {};
  }

  if (Array.isArray(extractedData)) {
    // Convert array to object with indexed keys
    const result: Record<string, any> = {};
    extractedData.forEach((item: any, idx: number) => {
      if (typeof item === 'object' && item !== null) {
        Object.keys(item).forEach(key => {
          result[`${idx}.${key}`] = item[key];
        });
      } else {
        result[`item_${idx}`] = item;
      }
    });
    return result;
  }

  if (typeof extractedData === 'object') {
    return extractedData;
  }

  // Primitive value
  return { value: extractedData };
}

/**
 * Normalize step alias for comparison
 * This pattern was duplicated 3+ times.
 */
export function normalizeStepAlias(stepAlias: string): string {
  return stepAlias.toLowerCase().trim();
}

/**
 * Parse error response from API
 * This pattern was duplicated 2+ times.
 */
export async function parseErrorResponse(response: Response, defaultMessage: string): Promise<string> {
  // Check for X-Error-Message header first
  const errorHeader = response.headers.get('X-Error-Message');
  if (errorHeader) {
    return errorHeader;
  }

  // Try to parse JSON response
  try {
    const responseClone = response.clone();
    const errorData = await responseClone.json();
    return errorData.message || errorData.error || errorData.detail || defaultMessage;
  } catch {
    // If not JSON, try text
    try {
      const errorText = await response.text();
      return errorText || `${response.status} ${response.statusText}`;
    } catch {
      return `${response.status} ${response.statusText}`;
    }
  }
}

/**
 * Extract callId from row data (before any path extraction)
 */
export function extractCallId(rowData: any, rowId: any): any {
  return rowData?._callId ?? rowId ?? 0;
}

/**
 * Normalize a single row from API response to DataSourceItemRow format
 * This pattern was duplicated 9+ times with slight variations.
 */
export function normalizeRow(
  row: any,
  context: NormalizeRowContext,
  options: {
    isWorkflowStep?: boolean;
    isAggregated?: boolean;
    outputStorageId?: string;
    extractPath?: boolean;
  } = {}
): DataSourceItemRow {
  const { tenantId, jsonPath, dataSourceId, index } = context;
  const { isWorkflowStep, isAggregated, outputStorageId, extractPath } = options;

  let rowData = row.data || {};

  // Extract callId BEFORE any path navigation
  const callId = extractCallId(rowData, row.id);
  const rowId = callId || row.id || (index !== undefined ? index + 1 : 0);

  // Extract data at path if needed
  if (extractPath && jsonPath && jsonPath !== '') {
    const extracted = extractDataAtPath(rowData, jsonPath);
    if (extracted !== null) {
      rowData = extracted;
    } else {
      rowData = {};
    }
  }

  // Ensure callId is in the data for display
  if (callId && !rowData.id) {
    rowData.id = callId;
  }
  if (callId && !rowData._callId) {
    rowData._callId = callId;
  }

  const result: DataSourceItemRow = {
    id: rowId,
    data_source_id: row.data_source_id ?? row.dataSourceId ?? row.storage_id ?? dataSourceId ?? 0,
    tenant_id: row.tenant_id ?? row.tenantId ?? tenantId,
    data: rowData,
    priority: row.priority ?? 0,
    created_at: row.created_at ?? row.createdAt ?? row.updated_at ?? row.updatedAt ?? new Date().toISOString(),
    updated_at: row.updated_at ?? row.updatedAt ?? null,
    row_index: row.row_index ?? row.rowIndex,
  };

  // Add optional fields
  if (jsonPath) {
    result._jsonPath = row.json_path ?? jsonPath;
  }
  if (isWorkflowStep) {
    result._isWorkflowStep = true;
  }
  if (isAggregated) {
    (result as any)._isAggregated = true;
  }
  if (outputStorageId ?? row.storage_id) {
    result._outputStorageId = outputStorageId ?? row.storage_id;
  }

  return result;
}

/**
 * Normalize an array of rows from API response
 */
export function normalizeRows(
  rows: any[],
  context: NormalizeRowContext,
  options: {
    isWorkflowStep?: boolean;
    isAggregated?: boolean;
    extractPath?: boolean;
  } = {}
): DataSourceItemRow[] {
  return (rows || []).map((row, index) =>
    normalizeRow(row, { ...context, index }, options)
  );
}

/**
 * Build URL with optional sort parameters
 */
export function appendSortParams(url: string, sortConfig: SortConfig | null): string {
  if (!sortConfig) return url;
  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}sortBy=${encodeURIComponent(sortConfig.key)}&sortOrder=${sortConfig.direction}`;
}

/**
 * Build pagination params for URL
 */
export function buildPaginationParams(page: number, pageSize: number, cursor?: string | null): string {
  let params = `limit=${pageSize}&page=${page}`;
  if (cursor) {
    params += `&cursor=${encodeURIComponent(cursor)}`;
  }
  return params;
}

/**
 * Get default sort config
 */
export function getDefaultSortConfig(): SortConfig {
  return { key: 'created_at', direction: 'desc' };
}

/**
 * Parse value according to its type (for editing)
 */
export function parseEditValue(val: string): any {
  // Try JSON first
  try {
    return JSON.parse(val);
  } catch {
    // Skip number parsing for date-like strings (e.g. "2026-03-19", "12:30")
    if (!/^\d{4}-\d{2}/.test(val) && !/^\d{1,2}:\d{2}/.test(val)) {
      const num = parseFloat(val);
      if (!isNaN(num) && isFinite(num) && String(num) === val.trim()) {
        return num;
      }
    }
    // Return as string
    return val;
  }
}

/**
 * Check if a column is a system/fixed column that cannot be deleted
 */
export function isSystemColumn(columnId: string): boolean {
  const systemColumns = ['id', 'priority', 'created_at', 'updated_at', 'checkbox', 'array_index', 'index'];
  return systemColumns.includes(columnId);
}

/**
 * Check if a column field should be excluded from display
 */
export function shouldExcludeField(field: string, jsonPath?: string): boolean {
  // Exclude internal fields starting with _
  if (field.startsWith('_')) return true;

  // In root mode, apply additional filters
  if (!jsonPath) {
    if (field === 'priority' || field === 'created_at') return true;
    if (field.startsWith('input.') && field !== 'input') return true;
    if (field.startsWith('metadata.') &&
        field !== 'metadata.statusMessage' &&
        field !== 'metadata.executionTimeMs') return true;
  }

  return false;
}
