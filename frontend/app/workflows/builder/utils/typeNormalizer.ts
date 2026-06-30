/**
 * Normalize column types to a consistent format.
 * This function aligns with normalizeFieldType from types.ts for consistency.
 *
 * Unified FieldType system: 'text' | 'number' | 'boolean' | 'datetime' | 'object' | 'array'
 */
export function normalizeColumnType(type: string | null | undefined): string {
  if (!type) return 'text';

  const normalized = type.toLowerCase().trim();

  // Map 'date' to 'datetime' for consistency
  if (normalized === 'date') return 'datetime';

  // Map JSON and object types to 'object'
  if (normalized === 'json' || normalized === 'obj') return 'object';

  // Map array types to 'array'
  if (normalized === 'arr') return 'array';

  // Map numeric types to 'number'
  if (normalized === 'integer' || normalized === 'int' || normalized === 'float' || normalized === 'double') {
    return 'number';
  }

  // Map string types to 'text'
  if (normalized === 'string' || normalized === 'str' || normalized === 'varchar' || normalized === 'char') {
    return 'text';
  }

  // Map bool types to 'boolean'
  if (normalized === 'bool') return 'boolean';

  // Return other types as-is (text, number, boolean, datetime, object, array)
  return normalized;
}

/**
 * Convert a string to snake_case
 * - Removes special characters
 * - Converts to lowercase
 * - Replaces spaces and special chars with underscores
 * - Removes leading/trailing underscores
 * - Collapses multiple underscores into one
 */
export function toSnakeCase(str: string): string {
  if (!str) return '';
  return str
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/_+/g, '_');
}

/**
 * Data column prefix used by the API for nested data fields.
 * Backend stores columns as "data.field_name" but flattens them in output.
 */
export const DATA_COLUMN_PREFIX = 'data.';

/**
 * Remove the data column prefix from a field name.
 * Backend flattens resolved inputs to top level, so expressions should not include data. prefix.
 *
 * @param field - The field name (e.g., "data.user_id" or "user_id")
 * @returns The field without the data prefix (e.g., "user_id")
 */
export function removeDataPrefix(field: string): string {
  if (!field) return '';
  return field.startsWith(DATA_COLUMN_PREFIX)
    ? field.slice(DATA_COLUMN_PREFIX.length)
    : field;
}

/**
 * Check if a field has the data column prefix.
 */
export function hasDataPrefix(field: string): boolean {
  return field?.startsWith(DATA_COLUMN_PREFIX) ?? false;
}

