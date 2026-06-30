/**
 * Helper functions for node creation
 * Extracted from NodeCreationService for reusability and cleaner code
 */

import type { XYPosition } from 'reactflow';

export const NODE_SPACING = { x: 300, y: 150 };
export const INITIAL_POSITION = { x: 100, y: 100 };

/**
 * Parse and validate position from plan data
 * Returns parsed position and whether it's a valid saved position
 */
export function parsePosition(
  position: { x?: number | string; y?: number | string } | undefined,
  fallbackX: number,
  fallbackY: number,
  contextLabel?: string
): { position: XYPosition; useSavedPosition: boolean } {
  if (!position) {
    // Return NaN so needsLayout() detects this node needs auto-layout.
    // Dagre will calculate proper positions based on graph topology.
    return { position: { x: NaN, y: NaN }, useSavedPosition: false };
  }

  const posX = position.x;
  const posY = position.y;
  const numX = typeof posX === 'number' ? posX : (typeof posX === 'string' ? parseFloat(posX) : NaN);
  const numY = typeof posY === 'number' ? posY : (typeof posY === 'string' ? parseFloat(posY) : NaN);

  if (!isNaN(numX) && !isNaN(numY) && isFinite(numX) && isFinite(numY)) {
    return { position: { x: numX, y: numY }, useSavedPosition: true };
  }

  if (contextLabel) {
    console.warn(`[NodeCreation] Invalid position for ${contextLabel}, using calculated position`);
  }
  // Return NaN so needsLayout() detects this node needs auto-layout.
  return { position: { x: NaN, y: NaN }, useSavedPosition: false };
}

/**
 * Convert params object to param expressions record
 */
export function inputToParamExpressions(params?: Record<string, any>): Record<string, string> {
  if (!params) return {};
  const expressions: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    if (typeof value === 'string') {
      expressions[key] = value;
    } else if (value && typeof value === 'object' && 'template' in value) {
      expressions[key] = (value as any).template;
    } else if (value !== undefined && value !== null) {
      // Use JSON.stringify for objects/arrays to preserve structure,
      // String() for primitives (numbers, booleans)
      expressions[key] = typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
    }
  }
  return expressions;
}

/**
 * Generate inner loop node ID (like demo4: core:while_X::step#1)
 */
export function makeInnerLoopNodeId(loopNodeId: string, childAlias: string, position: number): string {
  return `${loopNodeId}::${childAlias}#${position + 1}`;
}

/**
 * Generate a unique node ID with timestamp and random suffix
 */
export function generateNodeId(prefix: string, label: string): string {
  return `${prefix}-${label}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * CRUD operation normalization map
 */
export const CRUD_OPERATION_MAP: Record<string, string> = {
  'insert_row': 'create-row',
  'insert_rows': 'create-row',
  'read_row': 'read-row',
  'read_rows': 'read-row',
  'update_row': 'update-row',
  'update_rows': 'update-row',
  'delete_row': 'delete-row',
  'delete_rows': 'delete-row',
};

/**
 * Normalize CRUD operation name to current format
 */
export function normalizeCrudOperation(operation: string): string {
  return CRUD_OPERATION_MAP[operation] || operation;
}

/**
 * Operator mapping: plan format → frontend SQL_OPERATORS value
 */
const OPERATOR_MAP: Record<string, string> = {
  '=': '==',
  'eq': '==',
  'ne': '!=',
  'gt': '>',
  'lt': '<',
  'gte': '>=',
  'lte': '<=',
  'like': 'LIKE',
  'in': 'IN',
  'similar_to': 'SIMILAR_TO',
  'similarity': 'SIMILAR_TO',
};

/**
 * Normalize a where condition from plan format to frontend format:
 * - column: strip "data." prefix if present, then re-add it (backend columns use "data.column_name")
 * - operator: map "=" to "==" (SQL_OPERATORS uses "==")
 */
export function normalizeWhereCondition(where: { column?: string; operator?: string; value?: string; queryVector?: string; topK?: number }): {
  column: string;
  operator: string;
  value: string;
  queryVector?: string;
  topK?: number;
} {
  let column = where.column || '';
  // Always strip "data." prefix first to normalize, then re-add for backend format
  if (column.startsWith('data.')) {
    column = column.slice('data.'.length);
  }
  // Add "data." prefix for user data columns (not meta/id system columns)
  if (column && !column.startsWith('meta.') && column !== 'id') {
    column = `data.${column}`;
  }

  const rawOp = where.operator || '==';
  const operator = OPERATOR_MAP[rawOp] || rawOp;

  return {
    column,
    operator,
    value: where.value || '',
    ...(where.queryVector !== undefined && { queryVector: where.queryVector }),
    ...(where.topK !== undefined && { topK: where.topK }),
  };
}
