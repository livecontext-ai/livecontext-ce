import { useEffect } from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { removeDataPrefix } from '../../utils/typeNormalizer';

interface UseDataSourceColumnsInitProps {
  node: Node<BuilderNodeData> | null;
  columns: any[] | undefined;
  isTablesTrigger: boolean;
  dataSourceId: string | number | null | undefined;
  isMcpNode: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

// Root columns that are not nested in 'data'
const ROOT_COLUMNS = new Set(['id', 'data_source_id', 'tenant_id', 'priority', 'created_at', 'updated_at']);

/**
 * Converts a string to snake_case
 */
function toSnakeCase(str: string): string {
  if (!str) return '';
  return str
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/_+/g, '_');
}

/**
 * Hook to initialize default column expressions when datasource columns are loaded.
 * Only initializes if there are no existing expressions (first time setup).
 */
export function useDataSourceColumnsInit({
  node,
  columns,
  isTablesTrigger,
  dataSourceId,
  isMcpNode,
  onUpdate,
}: UseDataSourceColumnsInitProps): void {
  useEffect(() => {
    // Skip if conditions not met
    if (!columns || columns.length === 0) return;
    if (!isTablesTrigger || !dataSourceId) return;
    if (!node?.data || !node?.id) return;
    if (isMcpNode) return;

    const dataSourceData = (node.data as any)?.dataSourceData || {};
    const existingExpressions = dataSourceData.columnExpressions || {};

    // Only initialize if there are NO expressions at all (first time)
    const hasAnyExpressions = Object.keys(existingExpressions).length > 0;
    if (hasAnyExpressions) return;

    const newExpressions: Record<string, string> = {};
    const newLabels: Record<string, string> = {};

    columns.forEach((col: any) => {
      const columnField = col.field || col.col_id;
      const normalizedColumnField = toSnakeCase(columnField);
      const originalField = col.field || col.col_id;
      const isRoot = ROOT_COLUMNS.has(normalizedColumnField);

      // Generate expression path: NO data. prefix - backend flattens resolved inputs to top level
      const expressionPath = isRoot ? originalField : removeDataPrefix(originalField);

      // Generate label (remove "data." prefix for display)
      const defaultLabel = isRoot ? originalField : removeDataPrefix(originalField);
      newLabels[normalizedColumnField] = defaultLabel;

      // Generate expression with unified pattern: {{trigger:label.output.field}}
      const triggerLabel = normalizeLabel(node.data?.label || (node.data as any)?.name || 'default');
      newExpressions[normalizedColumnField] = `{{trigger:${triggerLabel}.output.${expressionPath}}}`;
    });

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...dataSourceData,
        columnExpressions: newExpressions,
        columnLabels: newLabels,
      },
    });
  }, [columns, isTablesTrigger, dataSourceId, node?.data, node?.id, onUpdate, isMcpNode]);
}
