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

interface FilterCondition {
  id: string;
  field: string;
  operator: string;
  value: string;
}

interface FilterParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const OPERATORS = [
  'equals',
  'notEquals',
  'contains',
  'notContains',
  'greaterThan',
  'lessThan',
  'greaterOrEqual',
  'lessOrEqual',
  'startsWith',
  'endsWith',
  'isEmpty',
  'isNotEmpty',
] as const;

const VALUELESS_OPERATORS = new Set(['isEmpty', 'isNotEmpty']);

/**
 * Generate a unique ID for a new condition
 */
function generateConditionId(): string {
  return `cond_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Operator i18n key map
 */
const OPERATOR_KEY_MAP: Record<string, string> = {
  equals: 'operatorEquals',
  notEquals: 'operatorNotEquals',
  contains: 'operatorContains',
  notContains: 'operatorNotContains',
  greaterThan: 'operatorGreaterThan',
  lessThan: 'operatorLessThan',
  greaterOrEqual: 'operatorGreaterOrEqual',
  lessOrEqual: 'operatorLessOrEqual',
  startsWith: 'operatorStartsWith',
  endsWith: 'operatorEndsWith',
  isEmpty: 'operatorIsEmpty',
  isNotEmpty: 'operatorIsNotEmpty',
};

/**
 * Form component for Filter node parameters.
 * Allows configuring conditions (field, operator, value) with AND/OR mode.
 */
export function FilterParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: FilterParametersFormProps) {
  const t = useTranslations('workflowBuilder');
  const tf = useTranslations('workflowBuilder.forms');

  // Get conditions from node data, initialize with one empty condition if none exist
  const conditions: FilterCondition[] = React.useMemo(() => {
    const existing = (data as any).filterConditions as FilterCondition[] | undefined;
    if (existing && existing.length > 0) {
      // Ensure every condition has a unique id (legacy data may lack ids)
      return existing.map((c, i) => c.id ? c : { ...c, id: `cond_legacy_${i}` });
    }
    return [{ id: generateConditionId(), field: '', operator: 'equals', value: '' }];
  }, [(data as any).filterConditions]);

  const mode: string = (data as any).filterMode ?? 'and';

  const canDelete = conditions.length > 1;

  const handleModeChange = React.useCallback((newMode: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      filterMode: newMode,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleAddCondition = React.useCallback(() => {
    if (isRunMode) return;
    const newCondition: FilterCondition = {
      id: generateConditionId(),
      field: '',
      operator: 'equals',
      value: '',
    };
    const updated = [...conditions, newCondition];
    onUpdate({
      ...data,
      filterConditions: updated,
    } as BuilderNodeData);
  }, [data, conditions, isRunMode, onUpdate]);

  const handleDeleteCondition = React.useCallback((conditionId: string) => {
    if (isRunMode || conditions.length <= 1) return;
    const updated = conditions.filter(c => c.id !== conditionId);
    onUpdate({
      ...data,
      filterConditions: updated,
    } as BuilderNodeData);
  }, [data, conditions, isRunMode, onUpdate]);

  const handleFieldChange = React.useCallback((conditionId: string, newField: string) => {
    if (isRunMode) return;
    const updated = conditions.map(c =>
      c.id === conditionId ? { ...c, field: newField } : c
    );
    onUpdate({
      ...data,
      filterConditions: updated,
    } as BuilderNodeData);
  }, [data, conditions, isRunMode, onUpdate]);

  const handleOperatorChange = React.useCallback((conditionId: string, newOperator: string) => {
    if (isRunMode) return;
    const updated = conditions.map(c => {
      if (c.id !== conditionId) return c;
      // Clear value when switching to a valueless operator
      const clearValue = VALUELESS_OPERATORS.has(newOperator);
      return { ...c, operator: newOperator, ...(clearValue ? { value: '' } : {}) };
    });
    onUpdate({
      ...data,
      filterConditions: updated,
    } as BuilderNodeData);
  }, [data, conditions, isRunMode, onUpdate]);

  const handleValueChange = React.useCallback((conditionId: string, newValue: string) => {
    if (isRunMode) return;
    const updated = conditions.map(c =>
      c.id === conditionId ? { ...c, value: newValue } : c
    );
    onUpdate({
      ...data,
      filterConditions: updated,
    } as BuilderNodeData);
  }, [data, conditions, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('inputData')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={(data as any).filterInput ?? ''}
          onChange={(val) => onUpdate({...data, filterInput: val} as BuilderNodeData)}
          placeholder={tf('expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ filterInput: (data as any).filterInput ?? '' })}
          handleId={`filter-input-${node.id}`}
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

      {/* Mode selector */}
      <div className="space-y-1">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('filterNode.mode')}
          </span>
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
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('filterNode.infoTitle')}</p>
                <p>{t('filterNode.infoDescription')}</p>
              </div>
            </PopoverContent>
          </Popover>
        </div>
        <Select
          value={mode}
          onValueChange={handleModeChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="and">{t('filterNode.modeAnd')}</SelectItem>
            <SelectItem value="or">{t('filterNode.modeOr')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Conditions header + add button */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('filterNode.conditions')}
        </span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddCondition();
            }}
            title={t('filterNode.addCondition')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Condition rows */}
      <div className="space-y-3">
        {conditions.map((condition) => {
          const isValueless = VALUELESS_OPERATORS.has(condition.operator);

          return (
            <div key={condition.id} className="flex flex-col gap-2 relative rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              {/* Field */}
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">
                  {t('filterNode.field')}
                </span>
                <Input
                  value={condition.field}
                  onChange={(e) => handleFieldChange(condition.id, e.target.value)}
                  placeholder={t('filterNode.fieldPlaceholder')}
                  className="w-full text-sm"
                  readOnly={isRunMode}
                />
              </div>

              {/* Operator */}
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">
                  {t('filterNode.operator')}
                </span>
                <Select
                  value={condition.operator}
                  onValueChange={(val) => handleOperatorChange(condition.id, val)}
                  disabled={isRunMode}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {OPERATORS.map((op) => (
                      <SelectItem key={op} value={op}>
                        {t(`filterNode.${OPERATOR_KEY_MAP[op]}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {/* Value (hidden for isEmpty/isNotEmpty) */}
              {!isValueless && (
                <div className="space-y-1">
                  <span className="text-xs text-slate-400 dark:text-slate-500">
                    {t('filterNode.value')}
                  </span>
                  <Input
                    value={condition.value}
                    onChange={(e) => handleValueChange(condition.id, e.target.value)}
                    placeholder={t('filterNode.valuePlaceholder')}
                    className="w-full text-sm"
                    readOnly={isRunMode}
                  />
                </div>
              )}

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
                      handleDeleteCondition(condition.id);
                    }}
                    disabled={!canDelete}
                    title={canDelete ? t('filterNode.removeCondition') : undefined}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
