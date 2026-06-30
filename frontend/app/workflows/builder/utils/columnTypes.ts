/**
 * Shared constants for column types and SQL operators
 * Used across CRUD operations (Create Column, Read/Update/Delete WHERE conditions)
 * Extracted from ParameterColumn.tsx to follow DRY principle
 */

import { IS_CE } from '@/lib/edition';

/**
 * Available column types for Create Column operation
 * Defines the data types that can be assigned to new columns.
 * Vector is self-hosted-only (managed cloud rejects it server-side) -
 * exclude it from the builder picker entirely on cloud.
 */
export const COLUMN_TYPES = [
  { value: 'text', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'json', label: 'JSON' },
  { value: 'rating', label: 'Rating' },
  { value: 'sentiment', label: 'Sentiment' },
  { value: 'file', label: 'File' },
  { value: 'image', label: 'Image' },
  { value: 'select', label: 'Select' },
  { value: 'badge', label: 'Badge' },
  { value: 'progress', label: 'Progress' },
  { value: 'tags', label: 'Tags' },
  { value: 'code', label: 'Code' },
  { value: 'link', label: 'Link' },
  ...(IS_CE ? [{ value: 'vector', label: 'Vector' }] : []),
] as const;

/**
 * SQL comparison operators for WHERE conditions
 * Used in Read Row, Update Row, and Delete Row operations.
 * SIMILAR_TO drives vector similarity search - self-hosted-only.
 */
export const SQL_OPERATORS = [
  { value: '==', label: '== (equals)' },
  { value: '!=', label: '!= (not equals)' },
  { value: '>', label: '> (greater than)' },
  { value: '<', label: '< (less than)' },
  { value: '>=', label: '>= (greater or equal)' },
  { value: '<=', label: '<= (less or equal)' },
  { value: 'LIKE', label: 'LIKE (pattern match)' },
  { value: 'IN', label: 'IN (list)' },
  ...(IS_CE ? [{ value: 'SIMILAR_TO', label: 'SIMILAR TO (vector)' }] : []),
  { value: 'IS NULL', label: 'IS NULL' },
  { value: 'IS NOT NULL', label: 'IS NOT NULL' },
] as const;

/**
 * Operators that don't require a value input
 * Used to conditionally hide the value field in WHERE condition builders
 */
export const NULL_OPERATORS = ['IS NULL', 'IS NOT NULL'] as const;

/**
 * Operators that use similarity-specific fields (queryVector, topK) instead of a simple value
 */
export const SIMILARITY_OPERATORS = ['SIMILAR_TO'] as const;

/**
 * Type definitions for TypeScript
 */
export type ColumnType = typeof COLUMN_TYPES[number]['value'];
export type SqlOperator = typeof SQL_OPERATORS[number]['value'];
