'use client';

import * as React from 'react';
import { Workflow } from 'lucide-react';
import { InfoPopover } from '@/components/ui/info-popover';
import type { Node } from 'reactflow';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';

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


  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('workflowTrigger.configuration')}</span>
        <InfoPopover label={t('workflowTrigger.configuration')} size="sm" side="bottom" align="end" contentClassName="w-[288px] p-3">
          <p className="mb-2 font-medium text-sm text-slate-700 dark:text-slate-200">{t('workflowTrigger.title')}</p>
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
        </InfoPopover>
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
