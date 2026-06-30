'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface SummarizeAggregation {
  id: string;
  field: string;
  operation: string;
  alias: string;
}

interface SummarizeGroupByField {
  id: string;
  field: string;
}

interface SummarizeParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const OPERATIONS = [
  'sum',
  'avg',
  'count',
  'min',
  'max',
  'countDistinct',
  'concatenate',
] as const;

/**
 * Generate a unique ID for a new item
 */
function generateId(): string {
  return `item_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Summarize node parameters.
 * Allows configuring aggregations (field, operation, alias) and group-by fields.
 */
export function SummarizeParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: SummarizeParametersFormProps) {
  const t = useTranslations('workflowBuilder.summarizeNode');
  const tf = useTranslations('workflowBuilder.forms');

  // Get aggregations from node data, initialize with one empty aggregation if none exist
  const aggregations: SummarizeAggregation[] = React.useMemo(() => {
    const existing = (data as any).summarizeAggregations as SummarizeAggregation[] | undefined;
    if (existing && existing.length > 0) {
      return existing.map((a, i) => ({
        id: a.id || `agg_${i}_${Date.now()}`,
        field: a.field ?? '',
        operation: a.operation ?? 'sum',
        alias: a.alias ?? '',
      }));
    }
    return [{ id: generateId(), field: '', operation: 'sum', alias: '' }];
  }, [(data as any).summarizeAggregations]);

  // Get group-by fields from node data
  const groupByFields: SummarizeGroupByField[] = React.useMemo(() => {
    const existing = (data as any).summarizeGroupBy as SummarizeGroupByField[] | undefined;
    if (existing && existing.length > 0) {
      return existing.map((f, i) => ({
        id: f.id || `gb_${i}_${Date.now()}`,
        field: f.field ?? '',
      }));
    }
    return [];
  }, [(data as any).summarizeGroupBy]);

  const canDeleteAggregation = aggregations.length > 1;

  // --- Aggregation handlers ---

  const handleAddAggregation = React.useCallback(() => {
    if (isRunMode) return;
    const newAggregation: SummarizeAggregation = {
      id: generateId(),
      field: '',
      operation: 'sum',
      alias: '',
    };
    const updated = [...aggregations, newAggregation];
    onUpdate({
      ...data,
      summarizeAggregations: updated,
    } as BuilderNodeData);
  }, [data, aggregations, isRunMode, onUpdate]);

  const handleDeleteAggregation = React.useCallback((aggregationId: string) => {
    if (isRunMode || aggregations.length <= 1) return;
    const updated = aggregations.filter(a => a.id !== aggregationId);
    onUpdate({
      ...data,
      summarizeAggregations: updated,
    } as BuilderNodeData);
  }, [data, aggregations, isRunMode, onUpdate]);

  const handleAggregationFieldChange = React.useCallback((aggregationId: string, newField: string) => {
    if (isRunMode) return;
    const updated = aggregations.map(a =>
      a.id === aggregationId ? { ...a, field: newField } : a
    );
    onUpdate({
      ...data,
      summarizeAggregations: updated,
    } as BuilderNodeData);
  }, [data, aggregations, isRunMode, onUpdate]);

  const handleOperationChange = React.useCallback((aggregationId: string, newOperation: string) => {
    if (isRunMode) return;
    const updated = aggregations.map(a =>
      a.id === aggregationId ? { ...a, operation: newOperation } : a
    );
    onUpdate({
      ...data,
      summarizeAggregations: updated,
    } as BuilderNodeData);
  }, [data, aggregations, isRunMode, onUpdate]);

  const handleAliasChange = React.useCallback((aggregationId: string, newAlias: string) => {
    if (isRunMode) return;
    const updated = aggregations.map(a =>
      a.id === aggregationId ? { ...a, alias: newAlias } : a
    );
    onUpdate({
      ...data,
      summarizeAggregations: updated,
    } as BuilderNodeData);
  }, [data, aggregations, isRunMode, onUpdate]);

  // --- Group-by handlers ---

  const handleAddGroupByField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: SummarizeGroupByField = {
      id: generateId(),
      field: '',
    };
    const updated = [...groupByFields, newField];
    onUpdate({
      ...data,
      summarizeGroupBy: updated,
    } as BuilderNodeData);
  }, [data, groupByFields, isRunMode, onUpdate]);

  const handleDeleteGroupByField = React.useCallback((fieldId: string) => {
    if (isRunMode) return;
    const updated = groupByFields.filter(f => f.id !== fieldId);
    onUpdate({
      ...data,
      summarizeGroupBy: updated,
    } as BuilderNodeData);
  }, [data, groupByFields, isRunMode, onUpdate]);

  const handleGroupByFieldChange = React.useCallback((fieldId: string, newValue: string) => {
    if (isRunMode) return;
    const updated = groupByFields.map(f =>
      f.id === fieldId ? { ...f, field: newValue } : f
    );
    onUpdate({
      ...data,
      summarizeGroupBy: updated,
    } as BuilderNodeData);
  }, [data, groupByFields, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('inputData')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={(data as any).summarizeInput ?? ''}
          onChange={(val) => onUpdate({...data, summarizeInput: val} as BuilderNodeData)}
          placeholder={tf('expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ summarizeInput: (data as any).summarizeInput ?? '' })}
          handleId={`summarize-input-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
        />
        <p className="text-xs text-slate-400">{tf('requiredArrayReference')}</p>
      </div>

      {/* Info header */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
            >
              <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
              <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
              <p>{t('infoDescription')}</p>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {/* Aggregations header + add button */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('aggregations')}
        </span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddAggregation();
            }}
            title={t('addAggregation')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Aggregation rows */}
      <div className="space-y-3">
        {aggregations.map((aggregation) => (
          <div key={aggregation.id} className="flex flex-col gap-2 relative rounded-lg border border-slate-200 dark:border-slate-700 p-3">
            {/* Field */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('field')}
              </span>
              <Input
                value={aggregation.field}
                onChange={(e) => handleAggregationFieldChange(aggregation.id, e.target.value)}
                placeholder={t('fieldPlaceholder')}
                className="w-full text-sm"
                readOnly={isRunMode}
              />
            </div>

            {/* Operation */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('operation')}
              </span>
              <Select
                value={aggregation.operation}
                onValueChange={(val) => handleOperationChange(aggregation.id, val)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {OPERATIONS.map((op) => (
                    <SelectItem key={op} value={op}>
                      {t(`operations.${op}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Alias */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('alias')}
              </span>
              <Input
                value={aggregation.alias}
                onChange={(e) => handleAliasChange(aggregation.id, e.target.value)}
                placeholder={t('aliasPlaceholder')}
                className="w-full text-sm"
                readOnly={isRunMode}
              />
            </div>

            {/* Delete button */}
            {!isRunMode && (
              <div className="flex justify-end">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteAggregation(aggregation.id);
                  }}
                  disabled={!canDeleteAggregation}
                  title={canDeleteAggregation ? t('removeAggregation') : undefined}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Group By header + add button */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('groupBy')}
        </span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddGroupByField();
            }}
            title={t('addGroupByField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Group-by field rows */}
      <div className="space-y-3">
        {groupByFields.length === 0 && (
          <p className="text-sm text-slate-400 dark:text-slate-500 italic">
            {t('noAggregations')}
          </p>
        )}
        {groupByFields.map((gbField) => (
          <div key={gbField.id} className="flex items-center gap-2">
            <Input
              value={gbField.field}
              onChange={(e) => handleGroupByFieldChange(gbField.id, e.target.value)}
              placeholder={t('groupByFieldPlaceholder')}
              className="w-full text-sm"
              readOnly={isRunMode}
            />
            {!isRunMode && (
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                onClick={(e) => {
                  e.stopPropagation();
                  handleDeleteGroupByField(gbField.id);
                }}
                title={t('addGroupByField')}
              >
                <Trash2 className="h-3 w-3" />
              </Button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
