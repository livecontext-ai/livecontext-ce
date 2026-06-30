'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface LimitParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for Limit node parameters.
 * Configures how many items to keep, from which end, and optional offset.
 */
export function LimitParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: LimitParametersFormProps) {
  const t = useTranslations('workflowBuilder.limitNode');
  const tf = useTranslations('workflowBuilder.forms');

  const count: number = (data as any).limitCount ?? 10;
  const from: string = (data as any).limitFrom ?? 'first';
  const offset: number = (data as any).limitOffset ?? 0;

  const handleCountChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        limitCount: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }

    onUpdate({
      ...data,
      limitCount: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleFromChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      limitFrom: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleOffsetChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        limitOffset: 0,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 0) {
      return;
    }

    onUpdate({
      ...data,
      limitOffset: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('inputData')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={(data as any).limitInput ?? ''}
          onChange={(val) => onUpdate({...data, limitInput: val} as BuilderNodeData)}
          placeholder={tf('expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ limitInput: (data as any).limitInput ?? '' })}
          handleId={`limit-input-${node.id}`}
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

      {/* Count */}
      <div className="space-y-1">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('count')}</span>
          <span className="text-sm text-slate-500 dark:text-slate-400">{tf('required')}</span>
        </div>
        <Input
          type="number"
          min="1"
          value={count}
          onChange={handleCountChange}
          className="w-full"
          placeholder={t('countPlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* From */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('from')}</span>
        <Select value={from} onValueChange={handleFromChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="first">{t('fromFirst')}</SelectItem>
            <SelectItem value="last">{t('fromLast')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Offset */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('offset')}</span>
        <Input
          type="number"
          min="0"
          value={offset}
          onChange={handleOffsetChange}
          className="w-full"
          placeholder={t('offsetPlaceholder')}
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t('offsetHelp')}
        </p>
      </div>
    </div>
  );
}
