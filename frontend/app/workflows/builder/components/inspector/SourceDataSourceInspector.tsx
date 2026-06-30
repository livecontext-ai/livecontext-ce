import * as React from 'react';
import clsx from 'clsx';
import { GripVertical, ArrowLeft } from 'lucide-react';
import { NodeIcon, getIconSlug } from '../nodes/shared';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { getFieldTypeColor, normalizeFieldType } from '../../types';
import { useDataSourceColumns } from '../../hooks/useDataSourceData';
import { normalizeColumnType, toSnakeCase, removeDataPrefix } from '../../utils/typeNormalizer';
import { useValidation } from '../../contexts/ValidationContext';
import { normalizeLabel } from '../../utils/labelNormalizer';

// Check if expression is a simple variable (exactly {{variable}})
const isSimpleVariable = (expression: string): boolean => {
  if (!expression) return false;
  // Remove all whitespace
  const trimmed = expression.replace(/\s+/g, '');
  // Check if it matches exactly {{something}} with no other content.
  // Mirrors backend TemplateEngine.EXPRESSION_PATTERN - accepts SpEL string literals.
  const match = trimmed.match(/^\{\{(?:'(?:[^'\\]|\\.)*'|[^}|])+?(?:\|[^}]*)?\}\}$/);
  return match !== null;
};

// Extract variable from expression (e.g., "e_commerce_users_database.created_at" from "{{e_commerce_users_database.created_at}}")
const extractVariable = (expression: string): string | null => {
  if (!expression) return null;
  const trimmed = expression.replace(/\s+/g, '');
  const match = trimmed.match(/^\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}$/);
  return match ? match[1] : null;
};

// Get column type from variable path (e.g., "e_commerce_users_database.created_at" -> get type of "created_at" column)
const getColumnTypeFromVariable = (variable: string, allColumns: any[]): string | null => {
  if (!variable || !allColumns) return null;
  // Split by dot to get the column name (last part)
  const parts = variable.split('.');
  const columnName = parts[parts.length - 1];
  // Normalize to find matching column
  const normalizedColumnName = toSnakeCase(columnName);
  const column = allColumns.find((col: any) => {
    const colField = toSnakeCase(col.field || col.col_id);
    return colField === normalizedColumnName;
  });
  return column?.type ? normalizeColumnType(column.type) : null;
};

interface SourceDataSourceInspectorProps {
  node: Node<BuilderNodeData>;
  onNavigateToNode?: (nodeId: string) => void;
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  isDraggable?: boolean;
  isRunMode?: boolean; // En mode run, désactiver le drag
}

export const SourceDataSourceInspector = React.memo(function SourceDataSourceInspector({
  node,
  onNavigateToNode,
  onSelectNode,
  isDraggable = true,
  isRunMode = false
}: SourceDataSourceInspectorProps) {
  // Use centralized validation context
  const { hasNodeErrors: checkNodeErrors } = useValidation();

  // Use either callback for navigation
  const handleNavigate = React.useCallback((nodeId: string, loopId?: string) => {
    if (onSelectNode) {
      onSelectNode(nodeId, loopId);
    } else if (onNavigateToNode) {
      onNavigateToNode(nodeId);
    }
  }, [onNavigateToNode, onSelectNode]);
  // Check if this is a datasource node
  const dataSourceData = (node.data as any)?.dataSourceData;
  const isTablesTrigger = node.data?.id?.startsWith('tables-trigger-') || !!dataSourceData;
  const dataSourceId = dataSourceData?.dataSourceId;

  const { data: columns, isLoading } = useDataSourceColumns(
    isTablesTrigger && dataSourceId ? dataSourceId : null
  );

  if (!isTablesTrigger || !dataSourceId) return null;

  // Get only active columns (columns with expressions in output)
  const allColumns = React.useMemo(() => {
    if (!columns || !node.data) return [];

    const dataSourceDataForActive = (node.data as any)?.dataSourceData || {};
    const expressions = dataSourceDataForActive.columnExpressions || {};
    const labels = dataSourceDataForActive.columnLabels || {};

    // Get only columns that have expressions (are in the output)
    const activeParameterFields = Object.keys(expressions);

    // Create a map of normalized column fields to column data for quick lookup
    const columnMap = new Map<string, any>();
    columns.forEach((col: any) => {
      const columnField = col.field || col.col_id;
      const normalizedColumnField = toSnakeCase(columnField);
      columnMap.set(normalizedColumnField, col);
    });

    // Process only active columns (those with expressions)
    return activeParameterFields.map((normalizedColumnField: string) => {
      const column = columnMap.get(normalizedColumnField);
      const expression = expressions[normalizedColumnField] || '';

      // Colonnes racine de la table (pas dans data)
      const ROOT_COLUMNS = new Set(['id', 'data_source_id', 'tenant_id', 'priority', 'created_at', 'updated_at']);
      const isRoot = ROOT_COLUMNS.has(normalizedColumnField);

      // Récupérer le field original depuis l'API (peut être "data.user_id", "data.data_XXX", ou "id")
      const originalField = column?.field || column?.col_id || normalizedColumnField;

      // Use unified pattern: {{trigger:label.output.field}}
      // E.g., {{trigger:my_orders.output.user_id}} (no data. prefix - backend flattens)
      const triggerLabel = node.data.label || 'default';
      const normalizedTriggerLabel = normalizeLabel(triggerLabel);

      // Build the path part (NO data. prefix - backend flattens resolved inputs to top level)
      const pathPart = isRoot ? originalField : removeDataPrefix(originalField);

      const fullPath = `{{trigger:${normalizedTriggerLabel}.output.${pathPart}}}`;

      // Get the custom label or fallback: remove "data." prefix for display
      const customLabel = labels[normalizedColumnField];
      const displayLabel = customLabel || (isRoot ? originalField : removeDataPrefix(originalField));

      // If it's a DB column, use its type; otherwise default to 'text' or infer from expression
      const columnType = column ? normalizeColumnType(column.type) : 'text';

      // Check if expression is a simple variable
      const isSimple = isSimpleVariable(expression);
      let displayType = 'unknown';

      if (isSimple) {
        // Extract the variable from expression
        const variable = extractVariable(expression);
        if (variable) {
          // Try to get the type from the referenced column
          const referencedType = getColumnTypeFromVariable(variable, columns);
          if (referencedType) {
            displayType = normalizeColumnType(referencedType);
          } else {
            // If variable not found in columns, use the parameter's column type as fallback
            displayType = columnType;
          }
        } else {
          displayType = columnType;
        }
      }
      // For complex expressions, displayType stays 'unknown' (same as OutputColumn.tsx)

      return {
        field: normalizedColumnField,
        label: displayLabel,
        type: displayType, // Use displayType instead of columnType to match output
        fullPath,
      };
    });
  }, [columns, node.data]);

  if (isLoading) {
    return (
      <div className="mb-3">
        <div className="pl-3 border-l border-slate-200 space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-5 w-full rounded bg-slate-200 animate-pulse" />)}
        </div>
      </div>
    );
  }

  if (!allColumns || allColumns.length === 0) {
    return null;
  }

  return (
    <div className="mb-3">
      <div className="mb-1 flex justify-start">
        <button
          className={clsx(
            "inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors",
            checkNodeErrors(node.id)
              ? "text-red-600 hover:text-red-700 hover:bg-red-50"
              : "text-slate-600 hover:text-slate-900 hover:bg-slate-100"
          )}
          title={`Go to ${node.data?.label || node.id}`}
          onClick={() => handleNavigate(node.id, (node.data as any)?._loopId)}
        >
          <ArrowLeft className="h-3 w-3 flex-shrink-0" />
          <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
          <span className="truncate max-w-[200px] font-medium">{node.data?.label || node.id}</span>
        </button>
      </div>
      <div>
        <div className="space-y-1">
          {allColumns.map((col: any, index: number) => (
            <div
              key={`${col.field}-${index}-${node.id}`}
              className={clsx(
                "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1 group",
                isDraggable
                  ? "cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800/50"
                  : "cursor-default"
              )}
              draggable={isDraggable}
              onDragStart={(e) => {
                if (!isDraggable) return;
                e.stopPropagation();
                e.dataTransfer.setData('text/plain', col.fullPath);
                e.dataTransfer.effectAllowed = 'copy';
              }}
              title={col.fullPath}
            >
              <div className="flex items-center gap-2 flex-1 min-w-0">
                {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 cursor-grab active:cursor-grabbing flex-shrink-0" />}
                <span className="truncate flex-1 min-w-0 text-sm" title={col.label}>
                  {col.label}
                </span>
              </div>
              <span className={clsx(
                "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                getFieldTypeColor(normalizeFieldType(col.type))
              )}>{normalizeFieldType(col.type)}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
});

