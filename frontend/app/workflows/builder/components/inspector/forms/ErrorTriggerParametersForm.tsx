'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X, AlertTriangle, Workflow } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';

interface ErrorTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:on_payment_failure") */
  triggerId?: string | null;
}

/**
 * Inspector form for the error trigger node.
 *
 * Mirrors WorkflowTriggerParametersForm - error trigger fires when the watched
 * parent workflow ends in FAILED or PARTIAL_SUCCESS. The parent workflow id is
 * stored in the trigger's `id` field (same convention as the workflow trigger),
 * which is set when the user picks the parent in the node-creation flow and
 * resolved here from `data.workflowData.workflowName` for display.
 *
 * ⚠️ BOOTSTRAP: error handlers need an existing WAITING_TRIGGER run before the
 * dispatcher can attach parent failures. The user must click Run on the handler
 * once after saving so it lands in WAITING_TRIGGER.
 */
export function ErrorTriggerParametersForm({
  data,
}: ErrorTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const workflowData = (data as any).workflowData;
  const workflowName = workflowData?.workflowName || 'Selected Workflow';

  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 320);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('errorTrigger.configuration')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => {
              e.stopPropagation();
              setIsInfoOpen(!isInfoOpen);
            }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('errorTrigger.moreInfo')}
          >
            <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
          </button>
          {isInfoOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
            <>
              <div
                className="fixed inset-0 z-[9998]"
                onClick={() => setIsInfoOpen(false)}
              />
              <div
                className="fixed z-[9999] w-80 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                    {t('errorTrigger.title')}
                  </span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setIsInfoOpen(false);
                    }}
                    className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    <X className="h-3.5 w-3.5 text-slate-400" />
                  </button>
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-2">
                  {t('errorTrigger.description')}
                </p>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2 mb-2">
                  <p className="text-xs font-semibold text-amber-600 dark:text-amber-400 mb-1 flex items-center gap-1">
                    <AlertTriangle className="h-3 w-3" />
                    {t('errorTrigger.bootstrapTitle')}
                  </p>
                  <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
                    {t('errorTrigger.bootstrapDescription')}
                  </p>
                </div>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{t('errorTrigger.availableOutputs')}</p>
                  <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
                    <li>• parentWorkflowId</li>
                    <li>• parentRunId</li>
                    <li>• status (FAILED | PARTIAL_SUCCESS)</li>
                    <li>• errorMessage</li>
                    <li>• triggeredAt</li>
                    <li>• failedSteps / completedSteps / totalSteps / skippedSteps</li>
                  </ul>
                </div>
              </div>
            </>,
            document.body
          )}
        </div>
      </div>

      {/* Watched (parent) Workflow */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('errorTrigger.watchedWorkflow')}</label>
        <div className="flex items-center gap-2 p-2 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
          <Workflow className="h-4 w-4 text-slate-500 dark:text-slate-400 flex-shrink-0" />
          <span className="text-sm text-slate-700 dark:text-slate-300 truncate">{workflowName}</span>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400">{t('errorTrigger.watchedWorkflowHint')}</p>
      </div>

      {/* Bootstrap reminder */}
      <div className="rounded-lg border border-amber-200 dark:border-amber-800/50 bg-amber-50 dark:bg-amber-950/30 p-3 space-y-1">
        <div className="flex items-center gap-1.5 text-amber-700 dark:text-amber-300">
          <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0" />
          <span className="text-sm font-semibold">{t('errorTrigger.bootstrapTitle')}</span>
        </div>
        <p className="text-xs text-amber-700 dark:text-amber-300 leading-relaxed">
          {t('errorTrigger.bootstrapDescription')}
        </p>
      </div>
    </div>
  );
}
