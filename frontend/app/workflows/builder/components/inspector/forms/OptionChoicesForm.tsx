'use client';

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData, OptionChoice } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface OptionChoicesFormProps {
  node: Node<BuilderNodeData>;
  connections: Connection[];
  isRunMode?: boolean;
  currentChoices: OptionChoice[];
  // Choice handlers
  handleAddChoice: () => void;
  handleDeleteChoice: (choiceId: string) => void;
  handleRenameChoice: (choiceId: string, newLabel: string) => void;
  handleExpressionChange: (choiceId: string, newExpression: string) => void;
  // Expression field props
  findUnknownVariables?: (expressions: Record<string, string>) => string[];
  draggingFromHandle?: string | null;
  hoveredTargetHandle?: string | null;
  handleHandleClick?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef?: (handleId: string, ref: HTMLElement | null) => void;
}

export function OptionChoicesForm({
  node,
  connections,
  isRunMode = false,
  currentChoices,
  handleAddChoice,
  handleDeleteChoice,
  handleRenameChoice,
  handleExpressionChange,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: OptionChoicesFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  return (
    <div className="space-y-3 pt-2 pb-0">
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('choices.title')}</p>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
          onClick={handleAddChoice}
          disabled={isRunMode}
          title={isRunMode ? t('readOnlyInRunMode') : t('choices.addChoice')}
        >
          <Plus className="h-4 w-4" />
        </Button>
      </div>
      <div className="space-y-4">
        {currentChoices.map((choice, index) => {
          const choiceHandleId = choice.id;

          return (
            <div key={choice.id} className="space-y-2">
              <div className="flex flex-col gap-1">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded flex-shrink-0 bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">
                    {index + 1}
                  </span>
                  <Input
                    className="flex w-full rounded-xl border-theme bg-[var(--bg-primary)] px-0 ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-[var(--accent-primary)] disabled:cursor-not-allowed disabled:opacity-50 text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    value={choice.label}
                    maxLength={50}
                    onChange={(event) => {
                      if (isRunMode) return;
                      handleRenameChoice(choice.id, event.target.value);
                    }}
                    onClick={(e) => e.stopPropagation()}
                    placeholder={t('choices.labelPlaceholder')}
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
                      handleDeleteChoice(choice.id);
                    }}
                    disabled={isRunMode || currentChoices.length <= 2}
                    title={isRunMode ? t('readOnlyInRunMode') : currentChoices.length <= 2 ? t('choices.minChoices') : t('choices.deleteChoice')}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="space-y-2">
                <div className="flex flex-col gap-2">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('condition')}</span>
                    <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                  </div>
                  <ExpressionEditor
                    value={choice.expression || ''}
                    onChange={(value) => {
                      if (isRunMode) return;
                      handleExpressionChange(choice.id, value);
                    }}
                    placeholder={t('enterExpression')}
                    className="w-full"
                    unknownVariables={findUnknownVariables ? findUnknownVariables({ [choice.id]: choice.expression || '' }) : []}
                    handleId={choiceHandleId}
                    connections={connections}
                    onHandleClick={handleHandleClick}
                    draggingFromHandle={draggingFromHandle ?? null}
                    onHandleMouseDown={handleHandleMouseDown}
                    onHandleMouseUp={handleHandleMouseUp}
                    hoveredTargetHandle={hoveredTargetHandle ?? null}
                    onSetHandleRef={handleSetHandleRef}
                    isRequired={true}
                    readOnly={isRunMode}
                  />
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
