'use client';

/**
 * DeleteRowForm - Delete rows from a datasource with WHERE condition
 *
 * CRUD Operation: DELETE
 * Features: WHERE condition builder with warning message
 * Reuses: WhereConditionBuilder (DRY principle)
 */

import * as React from 'react';
import { AlertTriangle } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';
import { WhereConditionBuilder, type WhereCondition } from './shared/WhereConditionBuilder';

interface DeleteRowFormProps {
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

export function DeleteRowForm({
  node,
  data,
  dataSourceId,
  columns,
  isLoadingColumns,
  onUpdate,
  isRunMode = false,
  findUnknownVariables,
  connectionProps,
}: DeleteRowFormProps) {
  // Extract WHERE condition from node data
  const whereCondition: WhereCondition = React.useMemo(() => {
    const condition = (data as any)?.dataSourceData?.whereCondition;
    return condition || { column: '', operator: '==', value: '' };
  }, [(data as any)?.dataSourceData?.whereCondition]);

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

  return (
    <div className="space-y-5 pt-2">
      {/* Warning message */}
      <div className="flex items-start gap-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
        <AlertTriangle className="h-4 w-4 text-red-500 flex-shrink-0 mt-0.5" />
        <div className="text-sm text-red-700 dark:text-red-300">
          <span className="font-semibold">Warning:</span> This action will permanently delete rows matching the condition below. This cannot be undone.
        </div>
      </div>

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
    </div>
  );
}
