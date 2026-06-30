'use client';

/**
 * CreateRowForm - Add new rows to a datasource table
 *
 * CRUD Operation: INSERT
 * Features: Multiple rows, each with values for all columns
 * Uses: extractColumnName, filterUserColumns (DRY principle)
 */

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import type { BuilderNodeData } from '../../../../types';
import { ExpressionField } from '../../ExpressionField';
import { EmptyState } from '../../../shared/EmptyState';
import { extractColumnName, filterUserColumns, SYSTEM_COLUMNS } from '../../../../utils/crudHelpers';

interface RowData {
  id: string;
  name: string;
  columns: Record<string, string>;
}

interface CreateRowFormProps {
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

export function CreateRowForm({
  node,
  data,
  dataSourceId,
  columns,
  isLoadingColumns,
  onUpdate,
  isRunMode = false,
  findUnknownVariables,
  connectionProps,
}: CreateRowFormProps) {
  // Extract rows from node data
  const rows: RowData[] = (data as any)?.dataSourceData?.rows || [];

  // Filter user-editable columns (exclude system columns)
  const userColumns = React.useMemo(() => {
    return filterUserColumns(columns || [], SYSTEM_COLUMNS.ALL);
  }, [columns]);

  // Add new row
  const handleAddRow = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRunMode) return;

    const rowNumber = rows.length + 1;
    const newRowId = `row${rowNumber}`;

    // Initialize column values as empty
    const columnValues: Record<string, string> = {};
    (columns || []).forEach((col: any) => {
      const columnField = col.field || col.col_id;
      const columnName = extractColumnName(columnField);
      if (!SYSTEM_COLUMNS.ALL.includes(columnName as any) && !SYSTEM_COLUMNS.ALL.includes(columnField as any)) {
        columnValues[columnName] = '';
      }
    });

    const newRows = [...rows, { id: newRowId, name: newRowId, columns: columnValues }];
    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        rows: newRows,
      },
    });
  }, [node.data, data, rows, columns, onUpdate, isRunMode]);

  // Delete row
  const handleDeleteRow = React.useCallback((rowIndex: number) => {
    if (isRunMode) return;

    const newRows = rows.filter((_: any, i: number) => i !== rowIndex);
    // Renumber remaining rows
    const renumberedRows = newRows.map((r: any, i: number) => ({
      ...r,
      id: `row${i + 1}`,
      name: `row${i + 1}`,
    }));

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        rows: renumberedRows,
      },
    });
  }, [node.data, data, rows, onUpdate, isRunMode]);

  // Update column value
  const handleColumnValueChange = React.useCallback((rowIndex: number, row: RowData, columnName: string, value: string) => {
    if (isRunMode) return;

    const newRows = [...rows];
    newRows[rowIndex] = {
      ...row,
      columns: {
        ...row.columns,
        [columnName]: value,
      },
    };

    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        rows: newRows,
      },
    });
  }, [node.data, data, rows, onUpdate, isRunMode]);

  // Loading state
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

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Rows</span>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
          onClick={handleAddRow}
          disabled={isRunMode}
          title={isRunMode ? 'Read-only in run mode' : 'Add new row'}
        >
          <Plus className="h-3 w-3" />
        </Button>
      </div>

      <div className="space-y-4">
        {rows.length === 0 ? (
          <EmptyState message="Click + to add a row" className="text-center py-4" />
        ) : (
          rows.map((row, rowIndex) => (
            <div key={row.id} className="flex flex-col gap-3 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-xl">
              {/* Row header with name and delete button */}
              <div className="flex items-center justify-between gap-2 border-b border-slate-200 dark:border-slate-700 pb-2">
                <span className="text-sm font-semibold text-slate-700 dark:text-slate-300">{row.name}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteRow(rowIndex);
                  }}
                  disabled={isRunMode}
                  title={isRunMode ? 'Read-only in run mode' : 'Delete row'}
                  style={{ display: isRunMode ? 'none' : 'inline-flex' }}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>

              {/* Column fields */}
              <div className="space-y-3">
                {userColumns.map((col: any) => {
                  const columnField = col.field || col.col_id;
                  const columnName = extractColumnName(columnField);
                  const displayName = extractColumnName(columnField);
                  const columnValue = row.columns?.[columnName] || '';

                  return (
                    <ExpressionField
                      key={`${row.id}-${columnName}`}
                      label={displayName}
                      value={columnValue}
                      onChange={(value) => handleColumnValueChange(rowIndex, row, columnName, value)}
                      nodeId={node.id}
                      fieldName={`${row.id}-${columnName}`}
                      placeholder={`Enter ${displayName}...`}
                      isRequired={false}
                      isRunMode={isRunMode}
                      typeHint={col.dataType || col.type || 'text'}
                      findUnknownVariables={findUnknownVariables}
                      connectionProps={connectionProps}
                    />
                  );
                })}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
