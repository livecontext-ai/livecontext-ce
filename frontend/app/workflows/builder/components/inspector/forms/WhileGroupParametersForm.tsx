'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Info } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { OptionalSection } from '../OptionalSection';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { useTranslations } from 'next-intl';

interface WhileGroupParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  onUpdate: (data: Partial<BuilderNodeData>) => void;
  getConditionExpression: () => string;
  handleConditionExpressionChange: (value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function WhileGroupParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  showOptionalParams,
  onToggleOptionalParams,
  onUpdate,
  getConditionExpression,
  handleConditionExpressionChange,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: WhileGroupParametersFormProps) {
  const t = useTranslations('whileGroupParametersForm');

  const handleMaxIterationsChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    if (value === '') {
      onUpdate({ maxIterations: undefined });
      return;
    }
    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) return;
    const clampedValue = Math.min(100, Math.max(1, numValue));
    onUpdate({ maxIterations: clampedValue });
  };

  return (
    <div className="space-y-4 pt-2">
      {/* Condition - required */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('conditionLabel')}
            </span>
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">
                  Expression that evaluates to true/false. The loop continues while this is true.
                </p>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('conditionRequired')}</span>
        </div>
        <ExpressionEditor
          value={getConditionExpression()}
          onChange={handleConditionExpressionChange}
          placeholder="{{mcp:step.output.hasMore}}"
          className="w-full"
          unknownVariables={findUnknownVariables({ condition: data.whileCondition || '' })}
          handleId={`while-condition-${node.id}`}
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
      </div>

      {/* Optional parameters */}
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={onToggleOptionalParams}
        count={1}
      >
        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t('maxIterationsLabel')}
              </span>
              <Popover>
                <PopoverTrigger asChild>
                  <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                    <Info className="h-3 w-3 text-slate-400" />
                  </button>
                </PopoverTrigger>
                <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                  <p className="text-xs text-slate-600 dark:text-slate-300">
                    Maximum number of iterations before the loop stops (default: 10).
                  </p>
                </PopoverContent>
              </Popover>
            </div>
            <span className="text-sm text-slate-400 dark:text-slate-500">{t('maxIterationsOptional')}</span>
          </div>
          <Input
            type="number"
            min="1"
            max="100"
            step="1"
            className="w-full"
            value={data.maxIterations ?? ''}
            onChange={handleMaxIterationsChange}
            placeholder={t('maxIterationsPlaceholder')}
            readOnly={isRunMode}
          />
        </div>
      </OptionalSection>
    </div>
  );
}
