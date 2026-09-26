import { resolveColumnType } from '@/utils/columnSpec';
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
 * A rendered row's data with the identity {@link normalizeRow} added to it removed.
 *
 * `normalizeRow` writes `_callId` into every row's data (and `id`, when the row carried none) so
 * the grid can display them. That is display state, not the user's data, and anything that writes
 * a row's data back to the server has to undo it first: a row PERSISTED with `_callId` is read
 * back through `extractCallId` on the next fetch and reports the id of the row it was copied from.
 * The copy then shares its source's identity everywhere the grid keys on `row.id` - selection,
 * inline edit, delete, and the "here is your copy" highlight - so it is invisible and the original
 * takes the writes meant for it.
 *
 * Only the keys normalizeRow actually wrote are removed (it records them as it goes). A user
 * column named `id` was left alone on the way in and survives the round trip, even when its value
 * happens to equal the row's own id.
 *
 * `_callId` goes unconditionally on top of that. It is a reserved internal key that
 * {@link extractCallId} reads back as the row's identity, so it is never legitimate stored data -
 * and a table built before this function existed can hold one that no normalizer recorded, which
 * would otherwise survive every future copy of that row.
 */
export function stripInjectedIdentity(row: DataSourceItemRow): Record<string, any> {
  const rest: Record<string, any> = { ...(row.data ?? {}) };
  for (const key of row._injectedDataKeys ?? []) {
    delete rest[key];
  }
  delete rest._callId;
  return rest;
}

/**
 * A row's data as it should be WRITTEN BACK: the user's fields, and nothing the read path added.
 *
 * Two things get added on the way in and must not be persisted by anything that copies a row.
 * {@link stripInjectedIdentity} covers the display identity. The other is the vector columns:
 * despite the name, the server's "vector preview" is the WHOLE embedding rendered as text
 * (`embedding::text`, no truncation) merged into the row's data, so a 1536-dimension column adds
 * roughly 18 KB of numbers to every row it fetches. Written back it becomes a literal string in
 * JSONB - invisible in the grid, since the vector cell renders `vec(N)` whatever the value holds,
 * and carried forward into every later copy of that row. The real embedding lives in its own table
 * and is not reproduced here at all: a copy is simply absent from similarity search until it is
 * re-embedded.
 */
export function toWritableRowData(
  row: DataSourceItemRow,
  columns?: Array<{ field: string; type?: string }>,
): Record<string, any> {
  const data = stripInjectedIdentity(row);
  for (const column of columns ?? []) {
    if (!column.type || resolveColumnType(column.type) !== 'vector') continue;
    delete data[column.field.startsWith('data.') ? column.field.slice('data.'.length) : column.field];
  }
  return data;
}

/**
 * The id a row SHOWS: its own `id` field when it has one, else the synthetic row id.
 *
 * Mirrors what the grid renders for the ID lane, so anything that reports a row's identity
 * elsewhere (CSV export, for one) names the same row the user is looking at rather than the
 * expansion counter underneath it.
 */
export function displayIdOf(row: DataSourceItemRow): string | number {
  // Where the grid reads the identity out of `row.data` - nested navigation, and workflow rows,
  // whose ID lane renders `row.data.id ?? row.id` at every level - so does this. At a plain
  // datasource root it does NOT: `data.id` there is either the id this code injected or a user
  // column that happens to be called `id`, while the grid shows the row's own id, and preferring
  // the field would make the export disagree with the screen.
  if (!row._jsonPath && !row._isWorkflowStep) return row.id;
  const own = row.data?.id;
  const injected = row._injectedDataKeys?.includes('id');
  // PRESENT, not truthy - `0` and `''` are ids a CRUD or agent step really can return, and showing
  // the expansion counter in their place is the very substitution this whole change removes. Only a
  // missing id (or a non-scalar, which React cannot render) falls back to the row's own.
  const isScalar = typeof own === 'string' || typeof own === 'number';
  return !injected && own !== undefined && own !== null && isScalar ? own : (row._displayId ?? row.id);
}

/**
 * Order two ids as {@link displayIdOf} returns them. Numbers compare numerically, and a run-log
 * id (`20.0.2` coordinates, `21:3` nested item) compares segment by segment on `.` and `:`,
 * numerically where both segments are finite numbers - so "21.10" sorts after "21.9" and "9"
 * before "21", which a plain string comparison gets wrong.
 */
export function compareDisplayIds(a: string | number, b: string | number): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  const aParts = String(a).split(/[.:]/);
  const bParts = String(b).split(/[.:]/);
  for (let i = 0; i < Math.max(aParts.length, bParts.length); i++) {
    if (aParts[i] === undefined) return -1;
    if (bParts[i] === undefined) return 1;
    const aNum = Number(aParts[i]);
    const bNum = Number(bParts[i]);
    const bothNumeric = aParts[i] !== '' && bParts[i] !== '' && Number.isFinite(aNum) && Number.isFinite(bNum);
    const order = bothNumeric
      ? aNum - bNum
      : aParts[i] < bParts[i] ? -1 : aParts[i] > bParts[i] ? 1 : 0;
    if (order !== 0) return order;
  }
  return 0;
}

/**
 * Give an item a display identity WITHOUT overwriting the one it already has.
 *
 * Used by the nested-navigation normalizers, which expand one stored row into N grid rows and need
 * a per-row id for React keys and selection. That id is a FALLBACK: an item that carries its own
 * `id` (table rows returned by a CRUD or agent step) must keep the real value, or the grid shows a
 * 1..N counter where the user came to read the database ids.
 *
 * Fills in only an ABSENT id - the same rule {@link normalizeRow} applies on the backend nested
 * routes. `data.id` is the item's own field, and `0` or `''` are values a CRUD or agent step really
 * returns: overwriting one destroys real data, because `stripInjectedIdentity` then takes the key
 * back out and the value is gone from every copy of the row too.
 *
 * The injected key is reported so {@link stripInjectedIdentity} removes exactly what was written
 * and nothing else.
 */
export function withDisplayIdentity(
  itemData: Record<string, any>,
  fallbackId: number,
): { data: Record<string, any>; injectedDataKeys: string[] } {
  const data: Record<string, any> = { ...itemData };
  if (data.id !== undefined && data.id !== null) {
    return { data, injectedDataKeys: [] };
  }
  data.id = fallbackId;
  return { data, injectedDataKeys: ['id'] };
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

  // Ensure callId is in the data for display. What gets written is recorded on the row
  // (`_injectedDataKeys`) so a writer can take exactly these keys back out - see
  // stripInjectedIdentity. Inferring it afterwards means comparing values, which cannot tell a
  // user column named `id` that happens to hold this row's id from one we wrote ourselves.
  //
  // PRESENT, not truthy, for the same reason as {@link withDisplayIdentity}: with `extractPath`
  // this `rowData` IS the navigated item, and `0` / `''` are ids a CRUD or agent step really
  // returns. Overwriting one and then declaring it injected destroys it - stripInjectedIdentity
  // takes the key back out and the value is gone from every copy of the row.
  const injectedDataKeys: string[] = [];
  if (callId && (rowData.id === undefined || rowData.id === null)) {
    rowData.id = callId;
    injectedDataKeys.push('id');
  }
  if (callId && !rowData._callId) {
    rowData._callId = callId;
    injectedDataKeys.push('_callId');
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
    _injectedDataKeys: injectedDataKeys,
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
 * Serialize an edited cell value for {@link parseEditValue}, its exact inverse.
 *
 * Cell editors may hand back a structured value (a media cell returns the asset map). `String()`
 * on one yields "[object Object]", which parseEditValue cannot recover, so the cell is destroyed
 * on save. Serializing keeps the round trip lossless.
 */
export function serializeEditValue(value: unknown): string {
  if (value !== null && typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
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
