/**
 * Shared utilities for CRUD operations
 * Extracted from ParameterColumn.tsx to follow DRY principle
 */

import { toSnakeCase, removeDataPrefix } from './typeNormalizer';

/**
 * System columns that should not be edited by users
 * Different CRUD operations use different subsets of these columns
 */
export const SYSTEM_COLUMNS = {
  /** All system columns including primary key */
  ALL: ['id', 'data_source_id', 'tenant_id', 'created_at', 'updated_at'] as const,

  /** Editable context columns (excludes 'id' which is never editable) */
  EDITABLE: ['data_source_id', 'tenant_id', 'created_at', 'updated_at'] as const,
} as const;

/**
 * Extracts the clean column name from a field path.
 * Handles API field paths that use "data." prefix.
 *
 * @example
 * extractColumnName('data.nom') // Returns: 'nom'
 * extractColumnName('id') // Returns: 'id'
 * extractColumnName('') // Returns: ''
 *
 * @param fieldPath - The field path from API response
 * @returns Clean column name without prefix
 */
export function extractColumnName(fieldPath: string): string {
  if (!fieldPath) return '';
  return removeDataPrefix(fieldPath);
}

/**
 * Filters out system columns from a columns array.
 * Used in CRUD operations to show only user-editable columns.
 *
 * @example
 * const userColumns = filterUserColumns(allColumns, SYSTEM_COLUMNS.EDITABLE);
 *
 * @param columns - Array of column objects from API
 * @param systemColumns - Array of system column names to filter out
 * @returns Filtered array containing only user-editable columns
 */
export function filterUserColumns(
  columns: any[],
  systemColumns: readonly string[]
): any[] {
  if (!columns || columns.length === 0) return [];

  return columns.filter((col: any) => {
    const columnField = col.field || col.col_id;

    // Check if column is in system columns list (direct match)
    if (systemColumns.includes(columnField)) return false;

    // Check if snake_case version is in system columns
    if (systemColumns.includes(toSnakeCase(columnField))) return false;

    return true;
  });
}
