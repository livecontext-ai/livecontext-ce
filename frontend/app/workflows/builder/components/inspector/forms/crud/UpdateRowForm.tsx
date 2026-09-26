'use client';

/**
 * UpdateRowForm - Update rows in a datasource with WHERE and SET
 *
 * CRUD Operation: UPDATE
 * Features: WHERE condition + SET columns
 * Reuses: WhereConditionBuilder (DRY principle)
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';
import { WhereConditionBuilder, type WhereCondition } from './shared/WhereConditionBuilder';
import { ExpressionField } from '../../ExpressionField';
import { EmptyState } from '../../../shared/EmptyState';
import { extractColumnName, filterUserColumns, SYSTEM_COLUMNS } from '../../../../utils/crudHelpers';
import { InfoPopover } from '@/components/ui/info-popover';

interface UpdateRowFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  dataSourceId: string;
  columns: any[];
  isLoadingColumns: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: any;
}

export function UpdateRowForm({
  node,
  data,
  dataSourceId,
  columns,
  isLoadingColumns,
  onUpdate,
  isRunMode = false,
  findUnknownVariables,
  connectionProps,
}: UpdateRowFormProps) {
  // Extract WHERE condition from node data
  const whereCondition: WhereCondition = React.useMemo(() => {
    const condition = (data as any)?.dataSourceData?.whereCondition;
    return condition || { column: '', operator: '==', value: '' };
  }, [(data as any)?.dataSourceData?.whereCondition]);

  // Extract SET columns from node data
  const setColumns = (data as any)?.dataSourceData?.setColumns || {};

  // Filter user-editable columns (exclude system columns)
  const userColumns = React.useMemo(() => {
    return filterUserColumns(columns || [], SYSTEM_COLUMNS.EDITABLE);
  }, [columns]);

  // Handle WHERE condition updates
  const handleWhereConditionUpdate = React.useCallback((condition: WhereCondition) => {
    if (isRunMode) return;

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        whereCondition: condition,
      },
    });
  }, [node.data, data, onUpdate, isRunMode]);

  // Handle SET column value change
  const handleSetColumnChange = React.useCallback((columnNameKey: string, value: string) => {
    if (isRunMode) return;

    const currentSetColumns = (data as any)?.dataSourceData?.setColumns || {};
    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        setColumns: {
          ...currentSetColumns,
          [columnNameKey]: value,
        },
      },
    });
  }, [node.data, data, onUpdate, isRunMode]);

  return (
    <div className="space-y-4 pt-2">
      {/* WHERE Condition Builder */}
      <WhereConditionBuilder
        nodeId={node.id}
        columns={columns}
        isLoadingColumns={isLoadingColumns}
        whereCondition={whereCondition}
        onUpdate={handleWhereConditionUpdate}
        isRunMode={isRunMode}
        findUnknownVariables={findUnknownVariables}
        connectionProps={connectionProps}
      />

      {/* SET Section - Show all columns */}
      <div className="space-y-3">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">SET Columns</span>
          <InfoPopover label="SET Columns" size="sm" side="right" align="start" contentClassName="w-64 p-3">
            <p className="text-sm text-slate-600 dark:text-slate-300">
              Empty fields will not be updated. Only fill in the columns you want to modify.
            </p>
          </InfoPopover>
        </div>
        <div className="space-y-3">
          {userColumns.length === 0 ? (
            <EmptyState message="No columns found" className="text-center py-4" />
          ) : (
            userColumns.map((col: any) => {
              const columnField = col.field || col.col_id;
              const columnNameKey = extractColumnName(columnField);
              const displayName = extractColumnName(columnField);
              const currentValue = setColumns[columnNameKey] || '';

              return (
                <ExpressionField
                  key={columnNameKey}
                  label={displayName}
                  value={currentValue}
                  onChange={(value) => handleSetColumnChange(columnNameKey, value)}
                  nodeId={node.id}
                  fieldName={`set-${columnNameKey}`}
                  placeholder={`New value for ${displayName}...`}
                  isRequired={false}
                  isRunMode={isRunMode}
                  findUnknownVariables={findUnknownVariables}
                  connectionProps={connectionProps}
                />
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
