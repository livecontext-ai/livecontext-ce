import * as React from 'react';
import clsx from 'clsx';
import { GripVertical } from 'lucide-react';
import { useDataSourceColumns, type DataSourceColumn } from '../../hooks/useDataSourceData';
import LoadingSpinner from '@/components/LoadingSpinner';
import { normalizeColumnType, toSnakeCase, removeDataPrefix, hasDataPrefix } from '../../utils/typeNormalizer';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { getFieldTypeColor, normalizeFieldType } from '../../types';
import { EmptyState } from '../shared/EmptyState';

interface DataSourceColumnsInspectorProps {
  dataSourceId: number;
  triggerLabel?: string; // Trigger label for unified syntax: {{trigger:label.output.field}}
  isDraggable?: boolean;
}

export const DataSourceColumnsInspector = React.memo(function DataSourceColumnsInspector({
  dataSourceId,
  triggerLabel,
  isDraggable = false,
}: DataSourceColumnsInspectorProps) {
  const { data: columns, isLoading, error } = useDataSourceColumns(dataSourceId);

  if (isLoading) {
    return (
      <div className="w-full">
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />)}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full">
        <div className="text-sm text-red-500 dark:text-red-400 italic pl-1">{String(error)}</div>
      </div>
    );
  }

  if (!columns || columns.length === 0) {
    return (
      <div className="w-full">
        <EmptyState message="No columns available" className="pl-1" />
      </div>
    );
  }

  // Colonnes racine de la table (pas dans data)
  const ROOT_COLUMNS = new Set(['id', 'data_source_id', 'tenant_id', 'priority', 'created_at', 'updated_at']);
  const isRootColumn = (columnField: string): boolean => {
    // Ne pas normaliser - vérifier directement si c'est une colonne racine
    const field = columnField || '';
    return ROOT_COLUMNS.has(field) || ROOT_COLUMNS.has(toSnakeCase(field));
  };

  const renderColumn = (column: DataSourceColumn, index: number) => {
    // Utiliser le field tel quel depuis l'API (peut être "data.user_id", "data.data_XXX", ou "id")
    const columnField = column.field || column.col_id || '';
    
    // Déterminer si c'est une colonne racine ou dans data
    const isRoot = isRootColumn(columnField);
    
    // Pour l'affichage et le path - PAS DE NORMALISATION
    let displayName: string;
    let pathPart: string;
    
    if (isRoot) {
      // Colonne racine : utiliser directement le nom
      displayName = columnField;
      pathPart = columnField;
    } else {
      // Colonne dans data : le field de l'API est "data.XXX"
      // Backend flattens resolved inputs to top level, so no data. prefix in expressions
      displayName = removeDataPrefix(columnField);
      pathPart = removeDataPrefix(columnField);
    }

    // For datasources, use unified pattern: {{trigger:label.output.field}}
    // E.g., {{trigger:my_orders.output.user_id}} (no data. prefix - backend flattens)
    const normalizedTriggerLabel = triggerLabel ? normalizeLabel(triggerLabel) : 'default';
    const fullPath = `{{trigger:${normalizedTriggerLabel}.output.${pathPart}}}`;
    
    // Use a unique key combining field and index to ensure uniqueness
    const uniqueKey = `${columnField}-${index}-${column.col_id || column.field}`;
    return (
      <div
        key={uniqueKey}
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
          e.dataTransfer.setData('text/plain', fullPath);
          e.dataTransfer.effectAllowed = 'copy';
        }}
        title={fullPath}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />}
          <span className="truncate flex-1 min-w-0 text-sm" title={displayName}>
            {displayName}
          </span>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          {column.type && (() => {
            const normalizedType = normalizeFieldType(normalizeColumnType(column.type));
            return (
              <span className={clsx(
                "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                getFieldTypeColor(normalizedType)
              )}>{normalizedType}</span>
            );
          })()}
        </div>
      </div>
    );
  };

  return (
    <div className="w-full">
      <div>
        <div className="space-y-1">
          {columns.map((column, index) => renderColumn(column, index))}
        </div>
      </div>
    </div>
  );
});

