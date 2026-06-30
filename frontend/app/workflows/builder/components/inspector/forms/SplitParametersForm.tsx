'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Info } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import { OptionalSection } from '../OptionalSection';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface SplitParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  onUpdate: (data: BuilderNodeData) => void;
  // Expression handlers
  getListExpression: () => string;
  handleListExpressionChange: (value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function SplitParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  showOptionalParams,
  onToggleOptionalParams,
  onUpdate,
  getListExpression,
  handleListExpressionChange,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: SplitParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const handleMaxItemsChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    if (value === '') {
      onUpdate({ ...data, maxItems: undefined });
      return;
    }
    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }
    // Allow up to 100 items max
    const clampedValue = Math.min(100, Math.max(1, numValue));
    onUpdate({ ...data, maxItems: clampedValue });
  };

  return (
    <div className="space-y-4 pt-2">
      {/* Required parameters - always shown */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('split.items')}</span>
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">{t('split.itemsDescription')}</p>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <ExpressionEditor
          value={getListExpression()}
          onChange={handleListExpressionChange}
          placeholder={t('split.placeholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ items: data.list || '' })}
          handleId={`items-expression-${node.id}`}
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

      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={onToggleOptionalParams}
        count={1}
      >
        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('split.maxItems')}</span>
              <Popover>
                <PopoverTrigger asChild>
                  <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                    <Info className="h-3 w-3 text-slate-400" />
                  </button>
                </PopoverTrigger>
                <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                  <p className="text-xs text-slate-600 dark:text-slate-300">{t('split.maxItemsDescription')}</p>
                </PopoverContent>
              </Popover>
            </div>
            <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
          </div>
          <Input
            type="number"
            min="1"
            max="100"
            step="1"
            className="w-full"
            value={data.maxItems ?? ''}
            onChange={handleMaxItemsChange}
            placeholder={t('split.allItems')}
            readOnly={isRunMode}
          />
        </div>
      </OptionalSection>
    </div>
  );
}
