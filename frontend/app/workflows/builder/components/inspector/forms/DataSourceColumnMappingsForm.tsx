'use client';

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { toSnakeCase, removeDataPrefix } from '../../../utils/typeNormalizer';

/**
 * Extract column name from field path (removes "data." prefix)
 */
const extractColumnName = (fieldPath: string): string => {
  if (!fieldPath) return '';
  return removeDataPrefix(fieldPath);
};

interface DataSourceColumnMappingsFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  columns: any[] | undefined;
  isLoadingColumns: boolean;
  isRunMode?: boolean;
  connections: Connection[];
  // Column handlers
  getColumnExpression: (field: string) => string;
  handleColumnExpressionChange: (field: string, value: string) => void;
  getColumnLabel: (field: string) => string;
  handleColumnLabelChange: (field: string, label: string) => void;
  handleDeleteColumn: (field: string) => void;
  handleAddColumn?: () => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

/**
 * DataSourceColumnMappingsForm - Form for configuring datasource column mappings
 * Used by triggers with tables/datasource type
 */
export function DataSourceColumnMappingsForm({
  node,
  data,
  columns,
  isLoadingColumns,
  isRunMode = false,
  connections,
  getColumnExpression,
  handleColumnExpressionChange,
  getColumnLabel,
  handleColumnLabelChange,
  handleDeleteColumn,
  handleAddColumn,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: DataSourceColumnMappingsFormProps) {
  if (isLoadingColumns) {
    return (
      <div className="space-y-4 pt-2">
        <div className="space-y-2">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-16 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />
          ))}
        </div>
      </div>
    );
  }

  // Get all active columns (including custom ones that don't match DB columns)
  const dataSourceDataForActive = (data.dataSourceData || {}) as Record<string, any>;
  const activeExpressions = dataSourceDataForActive.columnExpressions || {};
  const activeColumnFields = Object.keys(activeExpressions);
  const canDelete = activeColumnFields.length > 1;

  // Create a map of normalized column fields to column data for quick lookup
  const columnMap = new Map<string, any>();
  if (columns) {
    columns.forEach((col: any) => {
      const columnField = col.field || col.col_id;
      const normalizedColumnField = toSnakeCase(columnField);
      columnMap.set(normalizedColumnField, col);
    });
  }

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Column Mappings</span>
        {handleAddColumn && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              if (isRunMode) return;
              handleAddColumn();
            }}
            disabled={isRunMode}
            title={isRunMode ? 'Read-only in run mode' : 'Add new parameter'}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {activeColumnFields.map((normalizedColumnField: string, index: number) => {
          const column = columnMap.get(normalizedColumnField);
          const columnField = column?.field || column?.col_id || normalizedColumnField;
          const columnName = extractColumnName(columnField);
          const normalizedColumnName = toSnakeCase(columnName);
          const customLabel = getColumnLabel(normalizedColumnField);
          const displayLabel = customLabel || normalizedColumnName;
          const uniqueKey = `${normalizedColumnField}-${index}-${node?.id || ''}`;

          return (
            <div key={uniqueKey} className="flex flex-col gap-2 relative">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  <Input
                    value={displayLabel}
                    onChange={(e) => handleColumnLabelChange(normalizedColumnField, e.target.value)}
                    className="text-xs font-semibold text-slate-500 dark:text-slate-400 h-6 px-2 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    placeholder={normalizedColumnName}
                    readOnly={isRunMode}
                  />
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className="text-xs text-slate-500">Required</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-30 disabled:cursor-not-allowed"
                    onClick={(e) => {
                      e.stopPropagation();
                      if (isRunMode) return;
                      handleDeleteColumn(normalizedColumnField);
                    }}
                    disabled={isRunMode || !canDelete}
                    title={isRunMode ? 'Read-only in run mode' : (canDelete ? 'Delete column mapping' : 'At least one column mapping is required')}
                    style={{ display: isRunMode ? 'none' : 'inline-flex' }}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </div>
              <ExpressionEditor
                value={getColumnExpression(normalizedColumnField)}
                onChange={(value) => {
                  if (isRunMode) return;
                  handleColumnExpressionChange(normalizedColumnField, value);
                }}
                placeholder="Enter Expression..."
                className="w-full"
                unknownVariables={findUnknownVariables({ [normalizedColumnField]: getColumnExpression(normalizedColumnField) })}
                handleId={`column-${normalizedColumnField}-${node.id}`}
                connections={connections}
                onHandleClick={handleHandleClick}
                draggingFromHandle={draggingFromHandle}
                onHandleMouseDown={handleHandleMouseDown}
                onHandleMouseUp={handleHandleMouseUp}
                hoveredTargetHandle={hoveredTargetHandle}
                onSetHandleRef={handleSetHandleRef}
                isRequired={true}
                readOnly={isRunMode}
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}
