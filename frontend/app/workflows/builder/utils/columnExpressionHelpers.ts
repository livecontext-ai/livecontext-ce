/**
 * Column expression helper functions for datasource columns
 */

import { normalizeLabel } from './labelNormalizer';
import { toSnakeCase, removeDataPrefix } from './typeNormalizer';

// Root table columns (not in data object)
const ROOT_COLUMNS = new Set(['id', 'data_source_id', 'tenant_id', 'priority', 'created_at', 'updated_at']);

/**
 * Check if a column is a root column (not nested in data object)
 */
export const isRootColumn = (columnField: string): boolean => {
  const field = columnField || '';
  return ROOT_COLUMNS.has(field) || ROOT_COLUMNS.has(toSnakeCase(field));
};

/**
 * Initialize default column expressions and labels for datasource columns.
 * Generates expression paths using unified pattern: {{type:label.output.field}}
 */
export function initializeDefaultColumnExpressions(
  columns: any[],
  nodeLabel: string,
  isTriggerNode: boolean
): { expressions: Record<string, string>; labels: Record<string, string> } {
  const newExpressions: Record<string, string> = {};
  const newLabels: Record<string, string> = {};

  columns.forEach((col: any) => {
    const columnField = col.field || col.col_id;
    const isRoot = isRootColumn(columnField);
    const normalizedColumnField = toSnakeCase(columnField);

    // Generate expression path: NO data. prefix - backend flattens resolved inputs to top level
    const expressionPath = isRoot ? columnField : removeDataPrefix(columnField);

    // Generate default label: remove "data." prefix for display
    const defaultLabel = isRoot ? columnField : removeDataPrefix(columnField);
    newLabels[normalizedColumnField] = defaultLabel;

    // Generate expression with unified pattern: {{type:label.output.field}}
    const nodeLabelNormalized = normalizeLabel(nodeLabel || 'default');
    const prefix = isTriggerNode ? 'trigger' : 'mcp';
    newExpressions[normalizedColumnField] = `{{${prefix}:${nodeLabelNormalized}.output.${expressionPath}}}`;
  });

  return { expressions: newExpressions, labels: newLabels };
}

/**
 * Extract column name from field path (removes "data." prefix)
 */
export const extractColumnName = (fieldPath: string): string => {
  if (!fieldPath) return '';
  return removeDataPrefix(fieldPath);
};
