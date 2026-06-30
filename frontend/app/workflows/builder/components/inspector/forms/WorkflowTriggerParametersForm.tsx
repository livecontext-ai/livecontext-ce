'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X, Workflow } from 'lucide-react';
import type { Node } from 'reactflow';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';

const DATA_MODE_OPTIONS = [
  { value: 'all', label: 'Accept all data' },
  { value: 'filtered', label: 'Filtered data (coming soon)' },
];

interface WorkflowTriggerData {
  dataMode: 'all' | 'filtered';
}

interface WorkflowTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:my_workflow") */
  triggerId?: string | null;
}

export function WorkflowTriggerParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  triggerId,
}: WorkflowTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const workflowData = (data as any).workflowData;
  const workflowName = workflowData?.workflowName || 'Selected Workflow';

  const workflowTriggerData: WorkflowTriggerData = React.useMemo(() => {
    const existing = (data as any).workflowTriggerData as WorkflowTriggerData | undefined;
    return existing || {
      dataMode: 'all',
    };
  }, [(data as any).workflowTriggerData]);

  const handleDataModeChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      workflowTriggerData: {
        ...workflowTriggerData,
        dataMode: value as 'all' | 'filtered',
      },
    } as BuilderNodeData);
  }, [data, workflowTriggerData, isRunMode, onUpdate]);

  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 280);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('workflowTrigger.configuration')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => {
              e.stopPropagation();
              setIsInfoOpen(!isInfoOpen);
            }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('workflowTrigger.moreInfo')}
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
                className="fixed z-[9999] w-72 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                    {t('workflowTrigger.title')}
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
                  {t('workflowTrigger.description')}
                </p>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{t('workflowTrigger.availableOutputs')}</p>
                  <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
                    <li>• result</li>
                    <li>• status</li>
                  </ul>
                </div>
              </div>
            </>,
            document.body
          )}
        </div>
      </div>

      {/* Referenced Workflow */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('workflowTrigger.referencedWorkflow')}</label>
        <div className="flex items-center gap-2 p-2 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
          <Workflow className="h-4 w-4 text-slate-500 dark:text-slate-400 flex-shrink-0" />
          <span className="text-sm text-slate-700 dark:text-slate-300 truncate">{workflowName}</span>
        </div>
      </div>

      {/* Data Mode */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('workflowTrigger.dataMode')}</label>
        <Select
          value={workflowTriggerData.dataMode}
          onValueChange={handleDataModeChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={t('workflowTrigger.selectDataMode')} />
          </SelectTrigger>
          <SelectContent>
            {DATA_MODE_OPTIONS.map((option) => (
              <SelectItem
                key={option.value}
                value={option.value}
                disabled={option.value === 'filtered'}
              >
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
