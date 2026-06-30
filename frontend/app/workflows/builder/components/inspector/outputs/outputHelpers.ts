/**
 * Output helper functions for expression and variable handling
 */

import { normalizeColumnType, removeDataPrefix } from '../../../utils/typeNormalizer';

/**
 * Normalize string to snake_case
 */
export const toSnakeCase = (str: string): string => {
  if (!str) return '';
  return str
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/_+/g, '_');
};

/**
 * Check if expression is a simple variable (exactly {{variable}})
 */
export const isSimpleVariable = (expression: string): boolean => {
  if (!expression) return false;
  const trimmed = expression.replace(/\s+/g, '');
  // Mirrors backend TemplateEngine.EXPRESSION_PATTERN - accepts SpEL string literals.
  const match = trimmed.match(/^\{\{(?:'(?:[^'\\]|\\.)*'|[^}|])+?(?:\|[^}]*)?\}\}$/);
  return match !== null;
};

/**
 * Extract variable from expression (e.g., "field_name" from "{{mcp:label.output.field_name}}")
 */
export const extractVariable = (expression: string): string | null => {
  if (!expression) return null;
  const trimmed = expression.replace(/\s+/g, '');
  const match = trimmed.match(/^\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}$/);
  return match ? match[1] : null;
};

/**
 * Get column type from variable path
 * @param variable - Variable path like "mcp:label.field_name"
 * @param allColumns - Array of column definitions
 * @returns Column type or null if not found
 */
export const getColumnTypeFromVariable = (
  variable: string,
  allColumns: any[]
): string | null => {
  if (!variable || !allColumns) return null;
  const parts = variable.split('.');
  const columnName = parts[parts.length - 1];
  const normalizedColumnName = toSnakeCase(columnName);
  const column = allColumns.find((col: any) => {
    const colField = toSnakeCase(col.field || col.col_id);
    return colField === normalizedColumnName;
  });
  return column?.type ? normalizeColumnType(column.type) : null;
};

/**
 * Extract column name from field path (removes "data." prefix)
 */
export const extractColumnName = (fieldPath: string): string => {
  if (!fieldPath) return '';
  return removeDataPrefix(fieldPath);
};
