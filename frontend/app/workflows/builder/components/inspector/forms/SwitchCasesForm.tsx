'use client';

import * as React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData, SwitchCaseRow } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface SwitchCasesFormProps {
  node: Node<BuilderNodeData>;
  connections: Connection[];
  isRunMode?: boolean;
  currentCases: SwitchCaseRow[];
  switchExpression: string;
  // Case handlers
  getCaseHandleId: (caseRow: SwitchCaseRow, index: number) => string;
  getCaseValue: (caseId: string) => string;
  handleCaseValueChange: (caseId: string, value: string) => void;
  handleSwitchExpressionChange: (value: string) => void;
  handleAddCase: (afterIndex: number) => void;
  handleDeleteCase: (caseId: string) => void;
  handleRenameCase: (caseId: string, newLabel: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function SwitchCasesForm({
  node,
  connections,
  isRunMode = false,
  currentCases,
  switchExpression,
  getCaseHandleId,
  getCaseValue,
  handleCaseValueChange,
  handleSwitchExpressionChange,
  handleAddCase,
  handleDeleteCase,
  handleRenameCase,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: SwitchCasesFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const handleAddNewCase = () => {
    if (isRunMode) return;
    // Add a new case before the default case
    const defaultIndex = currentCases.findIndex(c => c.type === 'default');
    const insertIndex = defaultIndex >= 0 ? defaultIndex - 1 : currentCases.length - 1;
    handleAddCase(insertIndex);
  };

  return (
    <div className="space-y-4 pt-2 pb-0">
      {/* Switch Expression */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('switch.expression')}</span>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <ExpressionEditor
          value={switchExpression}
          onChange={(value) => {
            if (isRunMode) return;
            handleSwitchExpressionChange(value);
          }}
          placeholder={t('switch.expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ switchExpression })}
          handleId="switch-expression"
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

      {/* Cases */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('switch.cases')}</p>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={handleAddNewCase}
            disabled={isRunMode}
            title={isRunMode ? t('readOnlyInRunMode') : t('switch.addCase')}
          >
            <Plus className="h-4 w-4" />
          </Button>
        </div>
        <div className="space-y-4">
          {currentCases.map((caseRow, index) => {
            const isDefault = caseRow.type === 'default';
            const caseHandleId = getCaseHandleId(caseRow, index);

            return (
              <div key={caseRow.id} className="space-y-2">
                <div className="flex flex-col gap-1">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 flex-1 min-w-0">
                      <span className={`text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded flex-shrink-0 ${
                        isDefault
                          ? 'bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300'
                          : 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300'
                      }`}>
                        {isDefault ? t('switch.default') : t('switch.case')}
                      </span>
                      <Input
                        className="flex w-full rounded-xl border-theme bg-[var(--bg-primary)] px-0 ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-[var(--accent-primary)] disabled:cursor-not-allowed disabled:opacity-50 text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                        value={caseRow.label}
                        maxLength={50}
                        onChange={(event) => {
                          if (isRunMode) return;
                          handleRenameCase(caseRow.id, event.target.value);
                        }}
                        onClick={(e) => e.stopPropagation()}
                        placeholder={isDefault ? t('switch.defaultPlaceholder') : t('switch.casePlaceholder')}
                        readOnly={isRunMode}
                      />
                    </div>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed"
                      onClick={(e) => {
                        e.stopPropagation();
                        if (isRunMode) return;
                        handleDeleteCase(caseRow.id);
                      }}
                      disabled={isRunMode || (caseRow.type === 'case' && currentCases.filter(c => c.type === 'case').length <= 1)}
                      title={isRunMode ? t('readOnlyInRunMode') : (caseRow.type === 'case' && currentCases.filter(c => c.type === 'case').length <= 1) ? t('switch.minCases') : t('switch.deleteCase')}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
                <div className="space-y-2">
                  {!isDefault && (
                    <div className="flex flex-col gap-2">
                      <div className="flex items-center justify-between">
                        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('switch.value')}</span>
                        <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                      </div>
                      <Input
                        className="w-full rounded-xl border-theme bg-[var(--bg-primary)] px-3 ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-[var(--accent-primary)] disabled:cursor-not-allowed disabled:opacity-50 text-sm h-9"
                        value={getCaseValue(caseRow.id)}
                        onChange={(event) => {
                          if (isRunMode) return;
                          handleCaseValueChange(caseRow.id, event.target.value);
                        }}
                        onClick={(e) => e.stopPropagation()}
                        placeholder={t('switch.valuePlaceholder')}
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
    </div>
  );
}
