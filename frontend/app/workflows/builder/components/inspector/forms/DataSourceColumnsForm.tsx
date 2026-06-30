'use client';

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { toSnakeCase } from '../../../utils/typeNormalizer';

interface DataSourceColumn {
  field?: string;
  col_id?: string;
  header_name?: string;
}

interface DataSourceColumnsFormProps {
  node: Node<BuilderNodeData>;
  connections: Connection[];
  isRunMode?: boolean;
  isLoading: boolean;
  columns: DataSourceColumn[] | undefined;
  dataSourceData: {
    columnExpressions?: Record<string, string>;
    columnLabels?: Record<string, string>;
  } | undefined;
  // Column handlers
  getColumnExpression: (columnField: string) => string;
  getColumnLabel: (columnField: string) => string;
  handleColumnExpressionChange: (columnField: string, value: string) => void;
  handleColumnLabelChange: (columnField: string, label: string) => void;
  handleDeleteColumn: (columnField: string) => void;
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

export function DataSourceColumnsForm({
  node,
  connections,
  isRunMode = false,
  isLoading,
  columns,
  dataSourceData,
  getColumnExpression,
  getColumnLabel,
  handleColumnExpressionChange,
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
}: DataSourceColumnsFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  if (isLoading) {
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

  const activeExpressions = dataSourceData?.columnExpressions || {};
  const labels = dataSourceData?.columnLabels || {};
  const activeColumnFields = Object.keys(activeExpressions);
  const canDelete = activeColumnFields.length > 1;

  // Create a map of normalized column fields to column data for quick lookup
  const columnMap = new Map<string, DataSourceColumn>();
  if (columns) {
    columns.forEach((col) => {
      const columnField = col.field || col.col_id;
      if (columnField) {
        const normalizedColumnField = toSnakeCase(columnField);
        columnMap.set(normalizedColumnField, col);
      }
    });
  }

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('dataSource.columnMappings')}</span>
        {handleAddColumn && !isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddColumn();
            }}
            title={t('dataSource.addParameter')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {activeColumnFields.map((normalizedColumnField, index) => {
          const column = columnMap.get(normalizedColumnField);
          const columnName = column?.header_name || column?.field || column?.col_id || normalizedColumnField;
          const normalizedColumnName = toSnakeCase(columnName);
          const customLabel = labels[normalizedColumnField];
          const displayLabel = customLabel || normalizedColumnName;

          return (
            <label key={`${normalizedColumnField}-${index}-${node?.id || ''}`} className="flex flex-col gap-2 relative">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  <Input
                    value={displayLabel}
                    onChange={(e) => handleColumnLabelChange(normalizedColumnField, e.target.value)}
                    className="text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 px-2 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    placeholder={normalizedColumnName}
                    readOnly={isRunMode}
                  />
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDeleteColumn(normalizedColumnField);
                    }}
                    disabled={!canDelete || isRunMode}
                    title={canDelete ? t('dataSource.deleteColumn') : t('dataSource.minColumns')}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </div>
              <ExpressionEditor
                value={getColumnExpression(normalizedColumnField)}
                onChange={(value) => handleColumnExpressionChange(normalizedColumnField, value)}
                placeholder={t('enterExpression')}
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
            </label>
          );
        })}
      </div>
    </div>
  );
}
