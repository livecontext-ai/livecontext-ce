'use client';

/**
 * CreateColumnForm - Add new columns to a datasource table
 *
 * CRUD Operation: ALTER TABLE (Add Column)
 * Features: Column name, type, default value
 * Uses: COLUMN_TYPES from columnTypes.ts (DRY principle)
 */

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../../types';
import { ExpressionField } from '../../ExpressionField';
import { EmptyState } from '../../../shared/EmptyState';
import { COLUMN_TYPES } from '../../../../utils/columnTypes';

interface NewColumn {
  id: string;
  name: string;
  type: string;
  defaultValue?: string;
}

interface CreateColumnFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  dataSourceId: string;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: any;
}

export function CreateColumnForm({
  node,
  data,
  dataSourceId,
  onUpdate,
  isRunMode = false,
  findUnknownVariables,
  connectionProps,
}: CreateColumnFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Extract new columns from node data
  const newColumns: NewColumn[] = (data as any)?.dataSourceData?.newColumns || [];

  // Add new column
  const handleAddColumn = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRunMode) return;

    const colNumber = newColumns.length + 1;
    const newColumn: NewColumn = {
      id: `col${colNumber}`,
      name: `column${colNumber}`,
      type: 'text',
      defaultValue: '',
    };

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        newColumns: [...newColumns, newColumn],
      },
    });
  }, [node.data, data, newColumns, onUpdate, isRunMode]);

  // Delete column
  const handleDeleteColumn = React.useCallback((colIndex: number) => {
    if (isRunMode) return;

    const filteredColumns = newColumns.filter((_: any, i: number) => i !== colIndex);
    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        newColumns: filteredColumns,
      },
    });
  }, [node.data, data, newColumns, onUpdate, isRunMode]);

  // Update column field
  const handleColumnFieldChange = React.useCallback((colIndex: number, field: keyof NewColumn, value: string) => {
    if (isRunMode) return;

    const updatedColumns = [...newColumns];
    updatedColumns[colIndex] = { ...updatedColumns[colIndex], [field]: value };

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        newColumns: updatedColumns,
      },
    });
  }, [node.data, data, newColumns, onUpdate, isRunMode]);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('createColumn.title')}</span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
          onClick={handleAddColumn}
          disabled={isRunMode}
          title={isRunMode ? t('readOnlyInRunMode') : t('createColumn.addColumn')}
        >
          <Plus className="h-3 w-3" />
        </Button>
      </div>

      <div className="space-y-3">
        {newColumns.length === 0 ? (
          <EmptyState message={t('createColumn.emptyState')} className="text-center py-4" />
        ) : (
          newColumns.map((col, colIndex) => (
            <div key={col.id} className="flex flex-col gap-3 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-xl">
              {/* Column header with name and delete button */}
              <div className="flex items-center justify-between gap-2 border-b border-slate-200 dark:border-slate-700 pb-2">
                <span className="text-sm font-semibold text-slate-700 dark:text-slate-300">{t('createColumn.columnNumber', { number: colIndex + 1 })}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteColumn(colIndex);
                  }}
                  disabled={isRunMode}
                  title={isRunMode ? t('readOnlyInRunMode') : t('createColumn.deleteColumn')}
                  style={{ display: isRunMode ? 'none' : 'inline-flex' }}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>

              {/* Column Name */}
              <label className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('createColumn.name')}</span>
                  <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                </div>
                <Input
                  value={col.name}
                  onChange={(e) => handleColumnFieldChange(colIndex, 'name', e.target.value)}
                  placeholder={t('createColumn.namePlaceholder')}
                  readOnly={isRunMode}
                />
              </label>

              {/* Column Type */}
              <label className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('createColumn.type')}</span>
                  <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                </div>
                <Select
                  value={col.type}
                  onValueChange={(value) => handleColumnFieldChange(colIndex, 'type', value)}
                  disabled={isRunMode}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t('createColumn.selectType')} />
                  </SelectTrigger>
                  <SelectContent>
                    {COLUMN_TYPES.map((type) => (
                      <SelectItem key={type.value} value={type.value}>
                        {type.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>

              {/* Default Value */}
              <ExpressionField
                label={t('createColumn.defaultValue')}
                value={col.defaultValue || ''}
                onChange={(value) => handleColumnFieldChange(colIndex, 'defaultValue', value)}
                nodeId={node.id}
                fieldName={`col-${colIndex}-default`}
                placeholder={t('createColumn.defaultValuePlaceholder')}
                isRequired={false}
                isRunMode={isRunMode}
                findUnknownVariables={findUnknownVariables}
                connectionProps={connectionProps}
              />
            </div>
          ))
        )}
      </div>
    </div>
  );
}
