'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData, ConditionRow } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface DecisionBranchesFormProps {
  node: Node<BuilderNodeData>;
  connections: Connection[];
  isRunMode?: boolean;
  currentConditions: ConditionRow[];
  // Condition handlers
  getConditionHandleId: (condition: ConditionRow, index: number) => string;
  getConditionExpression: (conditionId: string) => string;
  handleConditionExpressionChange: (conditionId: string, value: string) => void;
  handleAddCondition: (type: 'elseif' | 'else', afterIndex: number) => void;
  handleDeleteCondition: (conditionId: string) => void;
  handleRenameCondition: (conditionId: string, newLabel: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function DecisionBranchesForm({
  node,
  connections,
  isRunMode = false,
  currentConditions,
  getConditionHandleId,
  getConditionExpression,
  handleConditionExpressionChange,
  handleAddCondition,
  handleDeleteCondition,
  handleRenameCondition,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: DecisionBranchesFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const handleAddElseIf = () => {
    if (isRunMode) return;
    const ifIndex = currentConditions.findIndex(c => c.type === 'if');
    if (ifIndex >= 0) {
      handleAddCondition('elseif', ifIndex);
    }
  };

  return (
    <div className="space-y-3 pt-2 pb-0">
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('decision.branches')}</p>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
          onClick={handleAddElseIf}
          disabled={isRunMode}
          title={isRunMode ? t('readOnlyInRunMode') : t('decision.addElseIf')}
        >
          <Plus className="h-4 w-4" />
        </Button>
      </div>
      <div className="space-y-4">
        {currentConditions.map((condition, index) => {
          const isIf = condition.type === 'if';
          const isElseIf = condition.type === 'elseif';
          const isElse = condition.type === 'else';
          const conditionHandleId = getConditionHandleId(condition, index);

          return (
            <div key={condition.id} className="space-y-2">
              <div className="flex flex-col gap-1">
                <div className="flex items-center justify-between gap-2">
                  <span className={clsx(
                    'text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded flex-shrink-0',
                    isElse
                      ? 'bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300'
                      : isIf
                        ? 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300'
                        : 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300'
                  )}>
                    {isElse ? t('decision.else') : isIf ? t('decision.if') : t('decision.elseIf')}
                  </span>
                  <Input
                    className="flex w-full rounded-xl border-theme bg-[var(--bg-primary)] px-0 ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-[var(--accent-primary)] disabled:cursor-not-allowed disabled:opacity-50 text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    value={condition.label}
                    maxLength={50}
                    onChange={(event) => {
                      if (isRunMode) return;
                      handleRenameCondition(condition.id, event.target.value);
                    }}
                    onClick={(e) => e.stopPropagation()}
                    placeholder={isIf ? t('decision.conditionPlaceholder') : isElseIf ? t('decision.conditionPlaceholder') : t('decision.fallbackPlaceholder')}
                    readOnly={isRunMode}
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed"
                    onClick={(e) => {
                      e.stopPropagation();
                      if (isRunMode) return;
                      handleDeleteCondition(condition.id);
                    }}
                    disabled={isRunMode || currentConditions.length <= 1 || condition.type === 'if'}
                    title={isRunMode ? t('readOnlyInRunMode') : (condition.type === 'if' ? t('decision.cannotDeleteIf') : t('decision.deleteBranch'))}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="space-y-2">
                {!isElse && (
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('condition')}</span>
                      <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                    </div>
                    <ExpressionEditor
                      value={getConditionExpression(condition.id)}
                      onChange={(value) => {
                        if (isRunMode) return;
                        handleConditionExpressionChange(condition.id, value);
                      }}
                      placeholder={t('enterExpression')}
                      className="w-full"
                      unknownVariables={findUnknownVariables({ [condition.id]: getConditionExpression(condition.id) })}
                      handleId={conditionHandleId}
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
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
