'use client';

/**
 * WhereConditionBuilder - Reusable WHERE condition editor
 *
 * Used by: ReadRowForm, UpdateRowForm, DeleteRowForm
 * Provides: Column selector + Operator selector + Value field
 * When operator is SIMILAR_TO: shows queryVector + topK instead of value
 * Follows DRY principle - single source of truth for WHERE condition UI
 */

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { ExpressionField } from '../../../ExpressionField';
import { SQL_OPERATORS, NULL_OPERATORS, SIMILARITY_OPERATORS } from '../../../../../utils/columnTypes';
import { extractColumnName, filterUserColumns, SYSTEM_COLUMNS } from '../../../../../utils/crudHelpers';

export interface WhereCondition {
  column: string;
  operator: string;
  value: string;
  queryVector?: string;
  topK?: number;
}

interface WhereConditionBuilderProps {
  /** Node ID for expression field handles */
  nodeId: string;

  /** All columns from datasource */
  columns: any[];

  /** Loading state for columns */
  isLoadingColumns: boolean;

  /** Current WHERE condition */
  whereCondition?: WhereCondition;

  /** Callback when condition changes */
  onUpdate: (condition: WhereCondition) => void;

  /** Read-only mode (disable editing) */
  isRunMode?: boolean;

  /** Expression validation function */
  findUnknownVariables: (expressions: Record<string, string>) => string[];

  /** Connection props for expression field */
  connectionProps: any;
}

export function WhereConditionBuilder({
  nodeId,
  columns,
  isLoadingColumns,
  whereCondition = { column: '', operator: '==', value: '' },
  onUpdate,
  isRunMode = false,
  findUnknownVariables,
  connectionProps,
}: WhereConditionBuilderProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Filter out system columns (users can't query by tenant_id, created_at, etc.)
  const userColumns = React.useMemo(() => {
    return filterUserColumns(columns || [], SYSTEM_COLUMNS.EDITABLE);
  }, [columns]);

  // Resolve the current column value to match a loaded column field.
  // Handles mismatch between "data.xxx" and "xxx" formats from plans/agent.
  const resolvedColumn = React.useMemo(() => {
    const current = whereCondition.column;
    if (!current || userColumns.length === 0) return current;

    // Check if current value already matches a column field directly
    const directMatch = userColumns.some((col: any) => (col.field || col.col_id) === current);
    if (directMatch) return current;

    // Try matching with "data." prefix added
    if (!current.startsWith('data.')) {
      const withPrefix = `data.${current}`;
      const prefixMatch = userColumns.some((col: any) => (col.field || col.col_id) === withPrefix);
      if (prefixMatch) return withPrefix;
    }

    // Try matching with "data." prefix stripped
    if (current.startsWith('data.')) {
      const stripped = current.slice('data.'.length);
      const strippedMatch = userColumns.some((col: any) => (col.field || col.col_id) === stripped);
      if (strippedMatch) return stripped;
    }

    return current;
  }, [whereCondition.column, userColumns]);

  // Handle column selection
  const handleColumnChange = React.useCallback((column: string) => {
    if (isRunMode) return;
    onUpdate({ ...whereCondition, column });
  }, [whereCondition, onUpdate, isRunMode]);

  // Handle operator selection
  const handleOperatorChange = React.useCallback((operator: string) => {
    if (isRunMode) return;
    onUpdate({ ...whereCondition, operator });
  }, [whereCondition, onUpdate, isRunMode]);

  // Handle value change
  const handleValueChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({ ...whereCondition, value });
  }, [whereCondition, onUpdate, isRunMode]);

  // Handle queryVector change
  const handleQueryVectorChange = React.useCallback((queryVector: string) => {
    if (isRunMode) return;
    onUpdate({ ...whereCondition, queryVector });
  }, [whereCondition, onUpdate, isRunMode]);

  // Handle topK change
  const handleTopKChange = React.useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const topK = Math.min(100, Math.max(1, parseInt(e.target.value) || 5));
    onUpdate({ ...whereCondition, topK });
  }, [whereCondition, onUpdate, isRunMode]);

  // Check what kind of value input to show
  const isSimilarity = SIMILARITY_OPERATORS.includes(whereCondition.operator as any);
  const requiresValue = !NULL_OPERATORS.includes(whereCondition.operator as any) && !isSimilarity;

  return (
    <div className="space-y-3">
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('where.title')}</span>
      <div className="p-3 bg-slate-50 dark:bg-slate-800/50 rounded-xl space-y-3">
        {/* Column selector */}
        <label className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('where.column')}</span>
          <Select
            value={resolvedColumn}
            onValueChange={handleColumnChange}
            disabled={isRunMode}
          >
            <SelectTrigger>
              <SelectValue placeholder={t('where.selectColumn')} />
            </SelectTrigger>
            <SelectContent>
              {isLoadingColumns ? (
                <SelectItem key="_loading" value="_loading" disabled>
                  {t('where.loadingColumns')}
                </SelectItem>
              ) : userColumns.length > 0 ? (
                userColumns.map((col: any) => {
                  const columnField = col.field || col.col_id;
                  const columnName = extractColumnName(columnField);
                  return (
                    <SelectItem key={columnField} value={columnField}>
                      {columnName}
                    </SelectItem>
                  );
                })
              ) : (
                <SelectItem key="_empty" value="_empty" disabled>
                  {t('where.noColumns')}
                </SelectItem>
              )}
            </SelectContent>
          </Select>
        </label>

        {/* Operator selector */}
        <label className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('where.operator')}</span>
          <Select
            value={whereCondition.operator}
            onValueChange={handleOperatorChange}
            disabled={isRunMode}
          >
            <SelectTrigger>
              <SelectValue placeholder={t('where.selectOperator')} />
            </SelectTrigger>
            <SelectContent>
              {SQL_OPERATORS.map((op) => (
                <SelectItem key={op.value} value={op.value}>
                  {op.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </label>

        {/* Standard value expression - for non-null, non-similarity operators */}
        {requiresValue && (
          <ExpressionField
            label={t('where.value')}
            value={whereCondition.value}
            onChange={handleValueChange}
            nodeId={nodeId}
            fieldName="where-value"
            placeholder={t('where.valuePlaceholder')}
            isRequired={true}
            isRunMode={isRunMode}
            findUnknownVariables={findUnknownVariables}
            connectionProps={connectionProps}
          />
        )}

        {/* Similarity fields - queryVector + topK */}
        {isSimilarity && (
          <>
            <ExpressionField
              label={t('where.queryVector')}
              value={whereCondition.queryVector || ''}
              onChange={handleQueryVectorChange}
              nodeId={nodeId}
              fieldName="where-queryVector"
              placeholder={t('where.queryVectorPlaceholder')}
              isRequired={true}
              isRunMode={isRunMode}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
            <label className="flex flex-col gap-1.5">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('where.topK')}</span>
              <Input
                type="number"
                min={1}
                max={100}
                value={whereCondition.topK ?? 5}
                onChange={handleTopKChange}
                disabled={isRunMode}
                placeholder="5"
              />
            </label>
          </>
        )}
      </div>
    </div>
  );
}
