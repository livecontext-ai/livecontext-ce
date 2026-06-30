'use client';

/**
 * ReadRowForm - Read/query rows from a datasource with WHERE and LIMIT
 *
 * CRUD Operation: SELECT/READ/FIND
 * Features: WHERE condition (with SIMILAR TO for vector search) + optional LIMIT
 * Reuses: WhereConditionBuilder (DRY principle)
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import type { BuilderNodeData } from '../../../../types';
import { WhereConditionBuilder, type WhereCondition } from './shared/WhereConditionBuilder';
import { OptionalSection } from '../../OptionalSection';

interface ReadRowFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  dataSourceId: string;
  columns: any[];
  isLoadingColumns: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  showOptionalParams?: boolean;
  setShowOptionalParams?: (show: boolean) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: any;
}

export function ReadRowForm({
  node,
  data,
  dataSourceId,
  columns,
  isLoadingColumns,
  onUpdate,
  isRunMode = false,
  showOptionalParams = false,
  setShowOptionalParams,
  findUnknownVariables,
  connectionProps,
}: ReadRowFormProps) {
  // Extract WHERE condition from node data
  const whereCondition: WhereCondition = React.useMemo(() => {
    const condition = (data as any)?.dataSourceData?.whereCondition;
    return condition || { column: '', operator: '==', value: '' };
  }, [(data as any)?.dataSourceData?.whereCondition]);

  // Extract LIMIT from node data (default: 50)
  const limit = (data as any)?.dataSourceData?.limit ?? 50;

  // Extract OFFSET from node data (default: 0)
  const offset = (data as any)?.dataSourceData?.offset ?? 0;

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

  // Handle LIMIT change
  const handleLimitChange = React.useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;

    const value = Math.min(50, Math.max(1, parseInt(e.target.value) || 1));
    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        limit: value,
      },
    });
  }, [node.data, data, onUpdate, isRunMode]);

  // Handle OFFSET change
  const handleOffsetChange = React.useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;

    const value = Math.max(0, parseInt(e.target.value) || 0);
    onUpdate({
      ...node.data,
      dataSourceData: {
        ...(data as any)?.dataSourceData,
        offset: value,
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

      {/* LIMIT & OFFSET Section - Optional */}
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={() => setShowOptionalParams?.(!showOptionalParams)}
        count={2}
      >
        <label className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Limit</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">Optional</span>
          </div>
          <Input
            type="number"
            min={1}
            max={50}
            value={limit}
            onChange={handleLimitChange}
            disabled={isRunMode}
            placeholder="50"
          />
        </label>
        <label className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Offset</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">Optional</span>
          </div>
          <Input
            type="number"
            min={0}
            value={offset}
            onChange={handleOffsetChange}
            disabled={isRunMode}
            placeholder="0"
          />
        </label>
      </OptionalSection>
    </div>
  );
}
